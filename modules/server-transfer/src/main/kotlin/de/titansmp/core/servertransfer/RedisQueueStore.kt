package de.titansmp.core.servertransfer

import de.titansmp.core.redis.RedisService
import io.lettuce.core.ScriptOutputType
import java.time.Instant
import java.util.UUID

/**
 * Network-wide queue state in the existing Redis service.
 *
 * Every operation that changes ownership or creates a lease is an atomic Lua script, so multiple
 * Velocity instances can allocate from the same queue without removing the same entry twice.
 * Queue membership remains transient and is never written to MariaDB.
 */
class RedisQueueStore(
    private val redis: RedisService,
    networkId: String,
) : QueueStore {
    private val commands get() = redis.commands

    init {
        require(networkId.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) { "Invalid network namespace ID" }
    }

    private val prefix = "titansmp:$networkId:server-transfer:v1"
    private val reservationsKey = "$prefix:reservations"
    private val sequenceKey = "$prefix:sequence"

    private fun queueKey(serverType: String) = "$prefix:queue:$serverType"
    private fun playerKey(playerUuid: UUID) = "$prefix:player:$playerUuid"

    override fun join(playerUuid: UUID, serverType: String, priority: Int, now: Instant): QueueJoinResult {
        require(priority in 0..de.titansmp.core.config.ServerTransferConfig.MAX_PRIORITY) { "Invalid queue priority" }
        val response = commands.eval<String>(
            JOIN_SCRIPT,
            ScriptOutputType.VALUE,
            arrayOf(queueKey(serverType), playerKey(playerUuid), reservationsKey, sequenceKey),
            prefix,
            playerUuid.toString(),
            serverType,
            priority.toString(),
        ) ?: throw IllegalStateException("Redis queue join returned no result")
        val fields = response.split('|')
        require(fields.size == 5) { "Malformed Redis queue join response" }
        val member = QueueMemberCodec.decode(fields[1])
        require(member.playerUuid == playerUuid) { "Redis returned a queue entry for another player" }
        return QueueJoinResult(
            entry = QueueEntry(member.playerUuid, serverType, member.priority, member.sequence),
            position = fields[2].toInt(),
            size = fields[3].toInt(),
            movedFrom = fields[4].takeIf { it.isNotBlank() },
        )
    }

    override fun leave(playerUuid: UUID): QueueLeaveResult? {
        val response = commands.eval<String>(
            LEAVE_SCRIPT,
            ScriptOutputType.VALUE,
            arrayOf(playerKey(playerUuid), reservationsKey),
            prefix,
            playerUuid.toString(),
        ).orEmpty()
        if (response.isBlank()) return null
        val separator = response.indexOf('|')
        if (separator <= 0) return null
        val serverType = response.substring(0, separator)
        val member = QueueMemberCodec.decode(response.substring(separator + 1))
        if (member.playerUuid != playerUuid) return null
        return QueueLeaveResult(QueueEntry(member.playerUuid, serverType, member.priority, member.sequence), true)
    }

    override fun status(playerUuid: UUID, now: Instant): QueueStatusSnapshot? {
        val existing = commands.get(playerKey(playerUuid)) ?: return null
        val separator = existing.indexOf('|')
        if (separator <= 0) return null
        val serverType = existing.substring(0, separator)
        val memberText = existing.substring(separator + 1)
        val member = QueueMemberCodec.decode(memberText)
        if (member.playerUuid != playerUuid) return null
        val rank = commands.zrank(queueKey(serverType), memberText) ?: return null
        val size = commands.zcard(queueKey(serverType)).toInt()
        val reservation = commands.hget(reservationsKey, playerUuid.toString())?.let(::parseReservation)
        return QueueStatusSnapshot(
            entry = QueueEntry(playerUuid, serverType, member.priority, member.sequence),
            position = rank.toInt() + 1,
            size = size,
            reserved = reservation != null && reservation.expiresAt.isAfter(now),
        )
    }

    override fun entries(serverType: String): List<QueueEntry> =
        commands.zrange(queueKey(serverType), 0, -1).map { memberText ->
            val member = QueueMemberCodec.decode(memberText)
            QueueEntry(member.playerUuid, serverType, member.priority, member.sequence)
        }

    override fun size(serverType: String): Int = commands.zcard(queueKey(serverType)).toInt()

    override fun findReservation(playerUuid: UUID, now: Instant): QueueReservation? {
        if (commands.get(playerKey(playerUuid)) == null) {
            commands.hdel(reservationsKey, playerUuid.toString())
            return null
        }
        return commands.hget(reservationsKey, playerUuid.toString())?.let(::parseReservation)
    }

    override fun reserveNext(serverType: String, reservationId: UUID, now: Instant, timeoutMs: Long): QueueReservation? {
        require(timeoutMs > 0) { "Reservation timeout must be positive" }
        val response = commands.eval<String>(
            RESERVE_SCRIPT,
            ScriptOutputType.VALUE,
            arrayOf(queueKey(serverType), reservationsKey),
            reservationId.toString(),
            now.toEpochMilli().toString(),
            timeoutMs.toString(),
            serverType,
        ).orEmpty()
        if (response.isBlank()) return null
        val fields = response.split('|')
        require(fields.size == 4) { "Malformed Redis reservation response" }
        val member = QueueMemberCodec.decode(fields[1])
        require(member.playerUuid.toString() == fields[0]) { "Redis returned a reservation for another player" }
        return QueueReservation(
            playerUuid = member.playerUuid,
            serverType = serverType,
            reservationId = reservationId,
            priority = member.priority,
            sequence = member.sequence,
            expiresAt = now.plusMillis(timeoutMs),
        )
    }

    override fun renew(reservation: QueueReservation, now: Instant, timeoutMs: Long): Boolean {
        require(timeoutMs > 0) { "Reservation timeout must be positive" }
        val result = commands.eval<Long>(
            RENEW_SCRIPT,
            ScriptOutputType.INTEGER,
            arrayOf(reservationsKey),
            reservation.reservationId.toString(),
            now.plusMillis(timeoutMs).toEpochMilli().toString(),
            reservation.playerUuid.toString(),
        )
        return result == 1L
    }

    override fun release(reservation: QueueReservation, removeFromQueue: Boolean): Boolean {
        val result = commands.eval<Long>(
            RELEASE_SCRIPT,
            ScriptOutputType.INTEGER,
            arrayOf(reservationsKey, playerKey(reservation.playerUuid)),
            reservation.reservationId.toString(),
            prefix,
            reservation.playerUuid.toString(),
            if (removeFromQueue) "1" else "0",
        )
        return result == 1L
    }

    override fun removeExpired(now: Instant): List<QueueReservation> {
        val response = commands.eval<String>(
            REMOVE_EXPIRED_SCRIPT,
            ScriptOutputType.VALUE,
            arrayOf(reservationsKey),
            now.toEpochMilli().toString(),
        ).orEmpty()
        if (response.isBlank()) return emptyList()
        return response.lineSequence().mapNotNull { line ->
            val parts = line.split('|', limit = 2)
            if (parts.size != 2) return@mapNotNull null
            parseReservation(parts[1])
        }.toList()
    }

    private fun parseReservation(value: String): QueueReservation? {
        val parts = value.split('|')
        if (parts.size != 4) return null
        val reservationId = runCatching { UUID.fromString(parts[0]) }.getOrNull() ?: return null
        val expiresAt = parts[1].toLongOrNull() ?: return null
        val serverType = parts[2].takeIf { it.isNotBlank() } ?: return null
        val member = runCatching { QueueMemberCodec.decode(parts[3]) }.getOrNull() ?: return null
        return QueueReservation(
            playerUuid = member.playerUuid,
            serverType = serverType,
            reservationId = reservationId,
            priority = member.priority,
            sequence = member.sequence,
            expiresAt = Instant.ofEpochMilli(expiresAt),
        )
    }

    private companion object {
        val JOIN_SCRIPT = """
            local existing = redis.call('GET', KEYS[2])
            local movedFrom = ''
            if existing then
              local separator = string.find(existing, '|', 1, true)
              if separator then
                local oldType = string.sub(existing, 1, separator - 1)
                local oldMember = string.sub(existing, separator + 1)
                if oldType == ARGV[3] then
                  local rank = redis.call('ZRANK', KEYS[1], oldMember)
                  if rank then
                    return table.concat({'0', oldMember, tostring(rank + 1), tostring(redis.call('ZCARD', KEYS[1])), ''}, '|')
                  end
                end
                movedFrom = oldType
                redis.call('ZREM', ARGV[1] .. oldType, oldMember)
                redis.call('HDEL', KEYS[3], ARGV[2])
              end
            end
            local sequence = redis.call('INCR', KEYS[4])
            local invertedPriority = 1000000 - tonumber(ARGV[4])
            local member = string.format('%010d', invertedPriority) .. ':' .. string.format('%020d', sequence) .. ':' .. ARGV[2]
            redis.call('ZADD', KEYS[1], 0, member)
            redis.call('SET', KEYS[2], ARGV[3] .. '|' .. member)
            local rank = redis.call('ZRANK', KEYS[1], member)
            return table.concat({'1', member, tostring(rank + 1), tostring(redis.call('ZCARD', KEYS[1])), movedFrom}, '|')
        """.trimIndent()

        val LEAVE_SCRIPT = """
            local existing = redis.call('GET', KEYS[1])
            redis.call('HDEL', KEYS[2], ARGV[2])
            if not existing then
              return ''
            end
            local separator = string.find(existing, '|', 1, true)
            if not separator then
              redis.call('DEL', KEYS[1])
              return ''
            end
            local serverType = string.sub(existing, 1, separator - 1)
            local member = string.sub(existing, separator + 1)
            redis.call('ZREM', ARGV[1] .. serverType, member)
            redis.call('DEL', KEYS[1])
            return serverType .. '|' .. member
        """.trimIndent()

        val RESERVE_SCRIPT = """
            local entries = redis.call('ZRANGE', KEYS[1], 0, -1)
            local now = tonumber(ARGV[2])
            for i, member in ipairs(entries) do
              local first = string.find(member, ':', 1, true)
              local second = string.find(member, ':', first + 1, true)
              local playerUuid = string.sub(member, second + 1)
              local current = redis.call('HGET', KEYS[2], playerUuid)
              if current then
                local p1 = string.find(current, '|', 1, true)
                local p2 = string.find(current, '|', p1 + 1, true)
                local expiry = p1 and p2 and tonumber(string.sub(current, p1 + 1, p2 - 1))
                if not expiry or expiry <= now then
                  redis.call('HDEL', KEYS[2], playerUuid)
                  current = nil
                end
              end
              if not current then
                local value = table.concat({ARGV[1], tostring(now + tonumber(ARGV[3])), ARGV[4], member}, '|')
                redis.call('HSET', KEYS[2], playerUuid, value)
                return table.concat({playerUuid, member, tostring(i), tostring(#entries)}, '|')
              end
            end
            return ''
        """.trimIndent()

        val RENEW_SCRIPT = """
            local current = redis.call('HGET', KEYS[1], ARGV[3])
            if not current then
              return 0
            end
            local p1 = string.find(current, '|', 1, true)
            if not p1 or string.sub(current, 1, p1 - 1) ~= ARGV[1] then
              return 0
            end
            local rest = string.sub(current, p1 + 1)
            local p2 = string.find(rest, '|', 1, true)
            if not p2 then
              return 0
            end
            redis.call('HSET', KEYS[1], ARGV[3], ARGV[1] .. '|' .. ARGV[2] .. '|' .. string.sub(rest, p2 + 1))
            return 1
        """.trimIndent()

        val RELEASE_SCRIPT = """
            local current = redis.call('HGET', KEYS[1], ARGV[3])
            if not current then
              return 0
            end
            local p1 = string.find(current, '|', 1, true)
            if not p1 or string.sub(current, 1, p1 - 1) ~= ARGV[1] then
              return 0
            end
            redis.call('HDEL', KEYS[1], ARGV[3])
            if ARGV[4] == '1' then
              local rest = string.sub(current, p1 + 1)
              local p2 = string.find(rest, '|', 1, true)
              local p3 = p2 and string.find(rest, '|', p2 + 1, true)
              if p3 then
                local serverType = string.sub(rest, p2 + 1, p3 - 1)
                local member = string.sub(rest, p3 + 1)
                local existing = redis.call('GET', KEYS[2])
                if existing == serverType .. '|' .. member then
                  redis.call('ZREM', ARGV[2] .. serverType, member)
                  redis.call('DEL', KEYS[2])
                end
              end
            end
            return 1
        """.trimIndent()

        val REMOVE_EXPIRED_SCRIPT = """
            local values = redis.call('HGETALL', KEYS[1])
            local now = tonumber(ARGV[1])
            local expired = {}
            for i = 1, #values, 2 do
              local playerUuid = values[i]
              local current = values[i + 1]
              local p1 = string.find(current, '|', 1, true)
              local p2 = p1 and string.find(current, '|', p1 + 1, true)
              local expiry = p1 and p2 and tonumber(string.sub(current, p1 + 1, p2 - 1))
              if not expiry or expiry <= now then
                redis.call('HDEL', KEYS[1], playerUuid)
                table.insert(expired, playerUuid .. '|' .. current)
              end
            end
            return table.concat(expired, '\n')
        """.trimIndent()
    }
}
