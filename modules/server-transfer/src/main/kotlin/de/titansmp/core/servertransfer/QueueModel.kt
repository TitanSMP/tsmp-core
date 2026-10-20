package de.titansmp.core.servertransfer

import de.titansmp.core.config.ServerTransferConfig
import java.time.Instant
import java.util.UUID

/** A registered backend as observed by the platform adapter. */
data class BackendSnapshot(
    val name: String,
    val serverType: String,
    val playerCount: Int,
    val maxPlayers: Int,
    val available: Boolean,
    val acceptingPlayers: Boolean,
) {
    init {
        require(name.isNotBlank()) { "Backend name must not be blank" }
        require(serverType.isNotBlank()) { "Backend server type must not be blank" }
        require(playerCount >= 0) { "Backend player count must not be negative" }
        require(maxPlayers > 0) { "Backend capacity must be positive" }
    }

    val hasCapacity: Boolean get() = playerCount < maxPlayers
}

/** Platform-neutral view of registered backends. Implementations live in the platform modules. */
interface BackendDirectory {
    /** All registered backends that have a configured type mapping. */
    fun snapshot(): List<BackendSnapshot>

    /** Configured type for a registered backend name, or `null` when the backend is unmapped. */
    fun typeForBackend(backendName: String): String?
}

/** Platform-neutral player connection operations used by the transfer service. */
interface PlayerConnectionGateway {
    fun currentServer(playerUuid: UUID): String?

    fun isOnline(playerUuid: UUID): Boolean

    /**
     * Requests a normal Velocity backend connection. The existing Data Bridge coordinator observes
     * the resulting pre-connect event and remains responsible for the prepare/ready/connect protocol.
     */
    fun connect(playerUuid: UUID, targetServerId: String): java.util.concurrent.CompletionStage<Boolean>
}

/** One queue entry. [sequence] is monotonic and gives FIFO order within a priority group. */
data class QueueEntry(
    val playerUuid: UUID,
    val serverType: String,
    val priority: Int,
    val sequence: Long,
) {
    init {
        require(serverType.isNotBlank()) { "Server type must not be blank" }
        require(priority in 0..ServerTransferConfig.MAX_PRIORITY) { "Invalid queue priority" }
        require(sequence >= 0) { "Queue sequence must not be negative" }
    }
}

data class QueueMember(
    val playerUuid: UUID,
    val priority: Int,
    val sequence: Long,
)

/** Lexicographic member encoding used by the Redis sorted set. */
object QueueMemberCodec {
    private const val PRIORITY_WIDTH = 10
    private const val SEQUENCE_WIDTH = 20

    fun encode(priority: Int, sequence: Long, playerUuid: UUID): String {
        require(priority in 0..ServerTransferConfig.MAX_PRIORITY) { "Invalid queue priority" }
        require(sequence >= 0) { "Queue sequence must not be negative" }
        val invertedPriority = ServerTransferConfig.MAX_PRIORITY - priority
        return invertedPriority.toString().padStart(PRIORITY_WIDTH, '0') +
            ":" + sequence.toString().padStart(SEQUENCE_WIDTH, '0') +
            ":" + playerUuid
    }

    fun decode(member: String): QueueMember {
        val first = member.indexOf(':')
        val second = if (first >= 0) member.indexOf(':', first + 1) else -1
        require(first > 0 && second > first) { "Malformed queue member" }
        val invertedPriority = member.substring(0, first).toIntOrNull()
            ?: throw IllegalArgumentException("Malformed queue priority in member")
        val sequence = member.substring(first + 1, second).toLongOrNull()
            ?: throw IllegalArgumentException("Malformed queue sequence in member")
        val playerUuid = UUID.fromString(member.substring(second + 1))
        val priority = ServerTransferConfig.MAX_PRIORITY - invertedPriority
        require(priority in 0..ServerTransferConfig.MAX_PRIORITY) { "Invalid queue priority in member" }
        require(sequence >= 0) { "Invalid queue sequence in member" }
        return QueueMember(playerUuid, priority, sequence)
    }
}

data class QueueJoinResult(
    val entry: QueueEntry,
    val position: Int,
    val size: Int,
    val movedFrom: String?,
) {
    init {
        require(position >= 1) { "Queue position must be positive" }
        require(size >= position) { "Queue size must not be smaller than the position" }
    }
}

data class QueueLeaveResult(
    val entry: QueueEntry,
    val reservationReleased: Boolean,
)

data class QueueStatusSnapshot(
    val entry: QueueEntry,
    val position: Int,
    val size: Int,
    val reserved: Boolean,
) {
    init {
        require(position >= 1) { "Queue position must be positive" }
        require(size >= position) { "Queue size must not be smaller than the position" }
    }
}

data class QueueReservation(
    val playerUuid: UUID,
    val serverType: String,
    val reservationId: UUID,
    val priority: Int,
    val sequence: Long,
    val expiresAt: Instant,
) {
    init {
        require(serverType.isNotBlank()) { "Server type must not be blank" }
        require(priority in 0..ServerTransferConfig.MAX_PRIORITY) { "Invalid queue priority" }
        require(sequence >= 0) { "Queue sequence must not be negative" }
    }

    val member: String get() = QueueMemberCodec.encode(priority, sequence, playerUuid)
}

/** The platform adapter's queue mutation contract. All methods are atomic at the store boundary. */
interface QueueStore {
    fun join(playerUuid: UUID, serverType: String, priority: Int, now: Instant): QueueJoinResult

    fun leave(playerUuid: UUID): QueueLeaveResult?

    fun status(playerUuid: UUID, now: Instant): QueueStatusSnapshot?

    fun entries(serverType: String): List<QueueEntry>

    fun size(serverType: String): Int

    fun findReservation(playerUuid: UUID, now: Instant): QueueReservation?

    /** Atomically reserves the first unreserved entry for [serverType]. */
    fun reserveNext(serverType: String, reservationId: UUID, now: Instant, timeoutMs: Long): QueueReservation?

    fun renew(reservation: QueueReservation, now: Instant, timeoutMs: Long): Boolean

    /** Releases a lease. When [removeFromQueue] is true the matching queue entry is removed too. */
    fun release(reservation: QueueReservation, removeFromQueue: Boolean): Boolean

    /** Atomically removes expired leases and returns them for logging. */
    fun removeExpired(now: Instant): List<QueueReservation>
}
