package de.titansmp.api.servertransfer

import de.titansmp.api.event.EventMetadata
import de.titansmp.api.event.EventType
import de.titansmp.api.event.NetworkEvent
import java.util.UUID

/** Published when a player's queue entry or queue position changes. */
data class ServerQueueUpdated(
    override val metadata: EventMetadata,
    val playerUuid: UUID,
    val serverType: String,
    val position: Int,
    val size: Int,
    val priority: Int,
) : NetworkEvent {
    init {
        require(serverType.isNotBlank()) { "Server type must not be blank" }
        require(position >= 1) { "Queue position must be positive" }
        require(size >= position) { "Queue size must not be smaller than the position" }
        require(priority >= 0) { "Queue priority must not be negative" }
    }

    companion object {
        val TYPE = EventType("server.queue.updated")
    }
}

/** Published when a player no longer has a queue entry. Safe to process more than once. */
data class ServerQueueLeft(
    override val metadata: EventMetadata,
    val playerUuid: UUID,
    val serverType: String,
    val reason: ServerQueueLeaveReason,
) : NetworkEvent {
    init {
        require(serverType.isNotBlank()) { "Server type must not be blank" }
    }

    companion object {
        val TYPE = EventType("server.queue.left")
    }
}

enum class ServerQueueLeaveReason {
    DISCONNECTED,
    LEFT,
    MOVED,
    TRANSFERRED,
}

/** Published after a queue slot has been reserved and a transfer is being requested. */
data class ServerQueueTransferStarted(
    override val metadata: EventMetadata,
    val playerUuid: UUID,
    val serverType: String,
    val targetServerId: String,
    val reservationId: UUID,
) : NetworkEvent {
    init {
        require(serverType.isNotBlank()) { "Server type must not be blank" }
        require(targetServerId.isNotBlank()) { "Target server ID must not be blank" }
    }

    companion object {
        val TYPE = EventType("server.queue.transfer.started")
    }
}

/** Published after the backend accepted the queued player and the queue entry was removed. */
data class ServerQueueTransferCompleted(
    override val metadata: EventMetadata,
    val playerUuid: UUID,
    val serverType: String,
    val targetServerId: String,
    val reservationId: UUID,
) : NetworkEvent {
    init {
        require(serverType.isNotBlank()) { "Server type must not be blank" }
        require(targetServerId.isNotBlank()) { "Target server ID must not be blank" }
    }

    companion object {
        val TYPE = EventType("server.queue.transfer.completed")
    }
}

/** Published when a queued transfer could not be started. The queue entry is retained. */
data class ServerQueueTransferFailed(
    override val metadata: EventMetadata,
    val playerUuid: UUID,
    val serverType: String,
    val targetServerId: String,
    val reservationId: UUID,
    val reason: String,
) : NetworkEvent {
    init {
        require(serverType.isNotBlank()) { "Server type must not be blank" }
        require(targetServerId.isNotBlank()) { "Target server ID must not be blank" }
        require(reason.isNotBlank()) { "Failure reason must not be blank" }
    }

    companion object {
        val TYPE = EventType("server.queue.transfer.failed")
    }
}
