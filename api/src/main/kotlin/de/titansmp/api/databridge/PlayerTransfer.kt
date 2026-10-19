package de.titansmp.api.databridge

import de.titansmp.api.event.EventMetadata
import de.titansmp.api.event.EventType
import de.titansmp.api.event.NetworkEvent
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletionStage

enum class PlayerTransferState { PREPARING, READY, CONNECTING, COMPLETED, FAILED }

data class PlayerTransfer(
    val transferId: UUID,
    val playerUuid: UUID,
    val sourceServerId: String,
    val targetServerId: String,
    val state: PlayerTransferState,
    val createdAt: Instant,
    val expectedRevision: Long? = null,
) {
    init {
        require(sourceServerId.isNotBlank() && targetServerId.isNotBlank())
        require(sourceServerId != targetServerId)
        require(expectedRevision == null || expectedRevision >= 0)
    }
}

/** Public transfer contract; platform adapters own the actual connection lifecycle. */
interface PlayerTransferService {
    fun findInProgress(playerUuid: UUID): PlayerTransfer?
    fun cancelTransfer(transferId: UUID, reason: String): CompletionStage<Boolean>
}

data class PlayerTransferPrepare(
    override val metadata: EventMetadata,
    val transferId: UUID,
    val playerUuid: UUID,
    val sourceServerId: String,
    val targetServerId: String,
    val timestamp: Instant,
) : NetworkEvent {
    companion object { val TYPE = EventType("player_transfer.prepare") }
}

data class PlayerTransferReady(
    override val metadata: EventMetadata,
    val transferId: UUID,
    val playerUuid: UUID,
    val sourceServerId: String,
    val targetServerId: String,
    val stateRevision: Long,
    val timestamp: Instant,
) : NetworkEvent {
    init { require(stateRevision >= 0) }
    companion object { val TYPE = EventType("player_transfer.ready") }
}

/** Confirms that the target has cached the incoming intent before Velocity allows the connection. */
data class PlayerTransferTargetPrepared(
    override val metadata: EventMetadata,
    val transferId: UUID,
    val playerUuid: UUID,
    val sourceServerId: String,
    val targetServerId: String,
    val timestamp: Instant,
) : NetworkEvent {
    companion object { val TYPE = EventType("player_transfer.target_prepared") }
}

data class PlayerTransferFailed(
    override val metadata: EventMetadata,
    val transferId: UUID,
    val playerUuid: UUID,
    val sourceServerId: String,
    val targetServerId: String,
    val reason: String,
    val timestamp: Instant,
) : NetworkEvent {
    companion object { val TYPE = EventType("player_transfer.failed") }
}

data class PlayerTransferCompleted(
    override val metadata: EventMetadata,
    val transferId: UUID,
    val playerUuid: UUID,
    val sourceServerId: String,
    val targetServerId: String,
    val stateRevision: Long,
    val timestamp: Instant,
) : NetworkEvent {
    init { require(stateRevision >= 0) }
    companion object { val TYPE = EventType("player_transfer.completed") }
}
