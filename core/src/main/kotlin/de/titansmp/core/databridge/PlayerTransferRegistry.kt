package de.titansmp.core.databridge

import de.titansmp.api.databridge.PlayerTransfer
import de.titansmp.api.databridge.PlayerTransferService
import de.titansmp.api.databridge.PlayerTransferState
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap

/** In-memory, bounded-lifetime projection of transfers coordinated through the event bus. */
class PlayerTransferRegistry(
    private val cancelPublisher: (PlayerTransfer, String) -> CompletionStage<Unit> = { _, _ -> java.util.concurrent.CompletableFuture.completedFuture(Unit) },
    private val terminalRetentionMs: Long = 30_000,
) : PlayerTransferService {
    private val transfers = ConcurrentHashMap<UUID, PlayerTransfer>()
    private val activeByPlayer = ConcurrentHashMap<UUID, UUID>()

    fun begin(playerUuid: UUID, sourceServerId: String, targetServerId: String, now: Instant = Instant.now()): PlayerTransfer? {
        if (sourceServerId == targetServerId) return null
        val transfer = PlayerTransfer(UUID.randomUUID(), playerUuid, sourceServerId, targetServerId, PlayerTransferState.PREPARING, now)
        if (activeByPlayer.putIfAbsent(playerUuid, transfer.transferId) != null) return null
        transfers[transfer.transferId] = transfer
        return transfer
    }

    fun ready(transferId: UUID, playerUuid: UUID, sourceServerId: String, targetServerId: String, revision: Long): PlayerTransfer? =
        update(transferId, playerUuid) { current ->
            if (current.state != PlayerTransferState.PREPARING || current.sourceServerId != sourceServerId || current.targetServerId != targetServerId || revision < 0) null
            else current.copy(state = PlayerTransferState.READY, expectedRevision = revision)
        }

    fun connecting(transferId: UUID): PlayerTransfer? = updateById(transferId) { current ->
        if (current.state == PlayerTransferState.READY) current.copy(state = PlayerTransferState.CONNECTING) else null
    }

    fun complete(transferId: UUID, playerUuid: UUID, revision: Long): Boolean {
        val completed = update(transferId, playerUuid) { current ->
            val expectedRevision = current.expectedRevision
            if (current.state != PlayerTransferState.CONNECTING || expectedRevision == null || revision < expectedRevision) null
            else current.copy(state = PlayerTransferState.COMPLETED)
        } ?: return false
        activeByPlayer.remove(playerUuid, transferId)
        scheduleRemoval(transferId, completed)
        return completed.state == PlayerTransferState.COMPLETED
    }

    fun fail(transferId: UUID, playerUuid: UUID): Boolean {
        val failed = update(transferId, playerUuid) { current ->
            if (current.state in setOf(PlayerTransferState.COMPLETED, PlayerTransferState.FAILED)) null
            else current.copy(state = PlayerTransferState.FAILED)
        } ?: return false
        activeByPlayer.remove(playerUuid, transferId)
        scheduleRemoval(transferId, failed)
        return failed.state == PlayerTransferState.FAILED
    }

    fun find(transferId: UUID): PlayerTransfer? = transfers[transferId]

    override fun findInProgress(playerUuid: UUID): PlayerTransfer? = activeByPlayer[playerUuid]?.let(transfers::get)

    override fun cancelTransfer(transferId: UUID, reason: String): CompletionStage<Boolean> {
        val current = transfers[transferId] ?: return java.util.concurrent.CompletableFuture.completedFuture(false)
        val transitioned = fail(transferId, current.playerUuid)
        if (!transitioned) return java.util.concurrent.CompletableFuture.completedFuture(false)
        val failed = requireNotNull(transfers[transferId])
        return cancelPublisher(failed, reason).thenApply { true }
    }

    private fun update(id: UUID, playerUuid: UUID, transition: (PlayerTransfer) -> PlayerTransfer?): PlayerTransfer? {
        if (activeByPlayer[playerUuid] != id) return null
        return updateById(id, transition)?.takeIf { it.playerUuid == playerUuid }
    }

    private fun updateById(id: UUID, transition: (PlayerTransfer) -> PlayerTransfer?): PlayerTransfer? {
        while (true) {
            val current = transfers[id] ?: return null
            val next = transition(current) ?: return null
            if (transfers.replace(id, current, next)) return next
        }
    }

    private fun scheduleRemoval(transferId: UUID, terminal: PlayerTransfer) {
        java.util.concurrent.CompletableFuture.delayedExecutor(terminalRetentionMs, java.util.concurrent.TimeUnit.MILLISECONDS).execute {
            transfers.remove(transferId, terminal)
        }
    }
}
