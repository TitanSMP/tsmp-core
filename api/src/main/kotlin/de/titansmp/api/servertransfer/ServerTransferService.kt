package de.titansmp.api.servertransfer

import java.util.UUID
import java.util.concurrent.CompletionStage

/**
 * Platform-independent entry point for network server transfers.
 *
 * Future features such as `/tpa`, `/tpahere`, `/home`, and `/rtp` call this contract. They must not
 * know about Velocity connection APIs, Redis queue keys, or the Data Bridge transfer protocol.
 * The coordinated Data Bridge prepare/ready/connect lifecycle remains owned by the existing
 * transfer coordinator; this service only requests a backend connection.
 */
interface ServerTransferService {
    /**
     * Queue-aware join for a configured server type. If an eligible backend has a free slot the
     * player is connected directly; otherwise the player joins the queue for that type.
     */
    fun requestJoin(playerUuid: UUID, serverType: String): CompletionStage<ServerTransferResult>

    /** Direct transfer to a registered, configured backend. No queue is used for this operation. */
    fun transferToServer(playerUuid: UUID, targetServerId: String): CompletionStage<ServerTransferResult>

    /** The player's current queue status, or `null` when they are not queued. */
    fun queueStatus(playerUuid: UUID): CompletionStage<ServerQueueStatus?>

    /** Removes the player from any queue. Returns `true` when an entry or reservation was removed. */
    fun leaveQueue(playerUuid: UUID): CompletionStage<Boolean>
}

/** Outcome of a transfer request. */
sealed interface ServerTransferResult {
    data class Connected(val serverType: String, val serverId: String) : ServerTransferResult

    data class Queued(val serverType: String, val position: Int, val size: Int) : ServerTransferResult

    data class AlreadyConnected(val serverType: String, val serverId: String) : ServerTransferResult

    data class Failed(val reason: ServerTransferFailure, val message: String? = null) : ServerTransferResult
}

enum class ServerTransferFailure {
    DISABLED,
    UNKNOWN_SERVER_TYPE,
    TARGET_NOT_FOUND,
    TARGET_FULL,
    PLAYER_NOT_CONNECTED,
    TRANSFER_IN_PROGRESS,
    QUEUE_UNAVAILABLE,
    CONNECTION_REJECTED,
    INTERNAL_ERROR,
}

/**
 * Queue position is one-based. [size] is the total number of entries in that server type's queue,
 * including the player's own entry and entries currently reserved for a transfer.
 */
data class ServerQueueStatus(
    val serverType: String,
    val position: Int,
    val size: Int,
    val priority: Int,
    val reserved: Boolean,
) {
    init {
        require(serverType.isNotBlank()) { "Server type must not be blank" }
        require(position >= 1) { "Queue position must be positive" }
        require(size >= position) { "Queue size must not be smaller than the position" }
        require(priority >= 0) { "Queue priority must not be negative" }
    }
}
