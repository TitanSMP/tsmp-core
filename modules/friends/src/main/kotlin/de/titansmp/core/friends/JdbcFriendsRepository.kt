package de.titansmp.core.friends

import de.titansmp.api.friends.*
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** MariaDB is authoritative. Pair lock rows serialize all transitions for a UUID pair. */
class JdbcFriendsRepository(private val dataSource: DataSource) {
    fun send(sender: UUID, target: UUID): FriendRequestResult = transaction(sender, target) { c ->
        if (sender == target) return@transaction FriendRequestResult.SELF
        if (blocked(c, sender, target) || blocked(c, target, sender)) return@transaction FriendRequestResult.BLOCKED
        if (friends(c, sender, target)) return@transaction FriendRequestResult.ALREADY_FRIENDS
        if (requestExists(c, target, sender)) return@transaction FriendRequestResult.INCOMING_REQUEST_EXISTS
        if (requestExists(c, sender, target)) return@transaction FriendRequestResult.DUPLICATE
        c.prepareStatement("INSERT INTO friend_requests(requester_uuid,target_uuid) VALUES(?,?)").use {
            it.setUuid(1, sender); it.setUuid(2, target); it.executeUpdate()
        }
        FriendRequestResult.CREATED
    }

    fun accept(player: UUID, requester: UUID): Boolean = transaction(player, requester) { c ->
        if (player == requester || blocked(c, player, requester) || blocked(c, requester, player)) return@transaction false
        if (!requestExists(c, requester, player)) return@transaction false
        val (low, high) = pair(player, requester)
        c.prepareStatement("INSERT IGNORE INTO friendships(player_low,player_high) VALUES(?,?)").use {
            it.setUuid(1, low); it.setUuid(2, high); it.executeUpdate()
        }
        deleteRequests(c, player, requester)
        true
    }

    fun reject(player: UUID, requester: UUID): Boolean = transaction(player, requester) { c ->
        c.prepareStatement("DELETE FROM friend_requests WHERE requester_uuid=? AND target_uuid=?").use {
            it.setUuid(1, requester); it.setUuid(2, player); it.executeUpdate() == 1
        }
    }

    fun remove(player: UUID, other: UUID): Boolean = transaction(player, other) { c ->
        val (low, high) = pair(player, other)
        c.prepareStatement("DELETE FROM friendships WHERE player_low=? AND player_high=?").use {
            it.setUuid(1, low); it.setUuid(2, high); it.executeUpdate() == 1
        }
    }

    fun block(player: UUID, other: UUID): Boolean = transaction(player, other) { c ->
        if (player == other) return@transaction false
        val (low, high) = pair(player, other)
        c.prepareStatement("DELETE FROM friendships WHERE player_low=? AND player_high=?").use { it.setUuid(1, low); it.setUuid(2, high); it.executeUpdate() }
        deleteRequests(c, player, other)
        c.prepareStatement("INSERT IGNORE INTO friend_blocks(blocker_uuid,blocked_uuid) VALUES(?,?)").use {
            it.setUuid(1, player); it.setUuid(2, other); it.executeUpdate() == 1
        }
    }

    fun unblock(player: UUID, other: UUID): Boolean = transaction(player, other) { c ->
        c.prepareStatement("DELETE FROM friend_blocks WHERE blocker_uuid=? AND blocked_uuid=?").use { it.setUuid(1, player); it.setUuid(2, other); it.executeUpdate() == 1 }
    }

    fun areFriends(a: UUID, b: UUID): Boolean = dataSource.connection.use { friends(it, a, b) }
    fun isBlocked(a: UUID, b: UUID): Boolean = dataSource.connection.use { blocked(it, a, b) }
    fun isBlockedEither(a: UUID, b: UUID): Boolean = dataSource.connection.use { blocked(it, a, b) || blocked(it, b, a) }

    fun friends(player: UUID, page: Int, size: Int): FriendsPage<Friend> {
        val p = page.coerceAtLeast(1); val s = size.coerceIn(1, 100)
        return dataSource.connection.use { c ->
            val total = c.prepareStatement("SELECT COUNT(*) FROM friendships WHERE player_low=? OR player_high=?").use { it.setUuid(1, player); it.setUuid(2, player); it.executeQuery().use { r -> r.next(); r.getLong(1) } }
            val rows = c.prepareStatement("SELECT IF(player_low=?,player_high,player_low) AS other_uuid,created_at FROM friendships WHERE player_low=? OR player_high=? ORDER BY created_at DESC LIMIT ? OFFSET ?").use {
                it.setUuid(1, player); it.setUuid(2, player); it.setUuid(3, player); it.setInt(4, s); it.setLong(5, (p - 1L) * s)
                it.executeQuery().use { r -> buildList { while (r.next()) add(Friend(r.getUuid("other_uuid"), r.getTimestamp("created_at").toInstant())) } }
            }
            FriendsPage(rows, p, s, total)
        }
    }

    fun requests(player: UUID, page: Int, size: Int): FriendsPage<FriendRequest> {
        val p = page.coerceAtLeast(1); val s = size.coerceIn(1, 100)
        return dataSource.connection.use { c ->
            val total = c.prepareStatement("SELECT COUNT(*) FROM friend_requests WHERE target_uuid=?").use { it.setUuid(1, player); it.executeQuery().use { r -> r.next(); r.getLong(1) } }
            val rows = c.prepareStatement("SELECT requester_uuid,target_uuid,created_at FROM friend_requests WHERE target_uuid=? ORDER BY created_at DESC LIMIT ? OFFSET ?").use {
                it.setUuid(1, player); it.setInt(2, s); it.setLong(3, (p - 1L) * s)
                it.executeQuery().use { r -> buildList { while (r.next()) add(FriendRequest(r.getUuid("requester_uuid"), r.getUuid("target_uuid"), r.getTimestamp("created_at").toInstant())) } }
            }
            FriendsPage(rows, p, s, total)
        }
    }

    private fun <T> transaction(a: UUID, b: UUID, block: (Connection) -> T): T = dataSource.connection.use { c ->
        c.autoCommit = false
        try {
            if (a != b) {
                val (low, high) = pair(a, b)
                c.prepareStatement("INSERT IGNORE INTO friend_pair_locks(player_low,player_high) VALUES(?,?)").use { it.setUuid(1, low); it.setUuid(2, high); it.executeUpdate() }
                c.prepareStatement("SELECT player_low FROM friend_pair_locks WHERE player_low=? AND player_high=? FOR UPDATE").use { it.setUuid(1, low); it.setUuid(2, high); it.executeQuery().close() }
            }
            block(c).also { c.commit() }
        } catch (t: Throwable) { c.rollback(); throw t }
    }
    private fun friends(c: Connection, a: UUID, b: UUID): Boolean {
        if (a == b) return false
        val (low, high) = pair(a, b)
        return c.prepareStatement("SELECT 1 FROM friendships WHERE player_low=? AND player_high=?").use { it.setUuid(1, low); it.setUuid(2, high); it.executeQuery().use { r -> r.next() } }
    }
    private fun blocked(c: Connection, a: UUID, b: UUID): Boolean = c.prepareStatement("SELECT 1 FROM friend_blocks WHERE blocker_uuid=? AND blocked_uuid=?").use { it.setUuid(1, a); it.setUuid(2, b); it.executeQuery().use { r -> r.next() } }
    private fun requestExists(c: Connection, a: UUID, b: UUID): Boolean = c.prepareStatement("SELECT 1 FROM friend_requests WHERE requester_uuid=? AND target_uuid=?").use { it.setUuid(1, a); it.setUuid(2, b); it.executeQuery().use { r -> r.next() } }
    private fun deleteRequests(c: Connection, a: UUID, b: UUID) = c.prepareStatement("DELETE FROM friend_requests WHERE (requester_uuid=? AND target_uuid=?) OR (requester_uuid=? AND target_uuid=?)").use { it.setUuid(1,a); it.setUuid(2,b); it.setUuid(3,b); it.setUuid(4,a); it.executeUpdate() }
    private fun pair(a: UUID, b: UUID): Pair<UUID, UUID> = if (a.toString() < b.toString()) a to b else b to a
    private fun java.sql.PreparedStatement.setUuid(index: Int, uuid: UUID) = setBytes(index, uuid.toBytes())
    private fun java.sql.ResultSet.getUuid(column: String): UUID = UUID.fromString(getBytes(column).toUuidString())
    private fun UUID.toBytes(): ByteArray = java.nio.ByteBuffer.allocate(16).putLong(mostSignificantBits).putLong(leastSignificantBits).array()
    private fun ByteArray.toUuidString(): String = java.nio.ByteBuffer.wrap(this).let { UUID(it.long, it.long).toString() }
}
