package de.titansmp.core.servertransfer

import de.titansmp.api.event.EventBus
import de.titansmp.api.event.EventMetadata
import de.titansmp.api.event.NetworkEvent
import de.titansmp.api.servertransfer.ServerQueueLeaveReason
import de.titansmp.api.servertransfer.ServerQueueLeft
import de.titansmp.api.servertransfer.ServerQueueStatus
import de.titansmp.api.servertransfer.ServerQueueTransferCompleted
import de.titansmp.api.servertransfer.ServerQueueTransferFailed
import de.titansmp.api.servertransfer.ServerQueueTransferStarted
import de.titansmp.api.servertransfer.ServerQueueUpdated
import de.titansmp.api.servertransfer.ServerTransferFailure
import de.titansmp.api.servertransfer.ServerTransferResult
import de.titansmp.api.servertransfer.ServerTransferService
import de.titansmp.core.config.ServerTransferConfig
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap

/** Lifecycle hooks owned by the platform adapter. Commands only use [ServerTransferService]. */
interface ServerTransferCoordinator : AutoCloseable {
    fun processQueues(serverType: String? = null)

    fun playerDisconnected(playerUuid: UUID)

    fun playerConnected(playerUuid: UUID, serverId: String)
}

/**
 * Queue-aware transfer orchestration. It selects backends from [BackendDirectory] snapshots and
 * delegates the actual connection to [PlayerConnectionGateway], which reuses the existing
 * coordinated Data Bridge transfer protocol in the Velocity adapter.
 */
class DefaultServerTransferService(
    private val config: ServerTransferConfig,
    private val directory: BackendDirectory,
    private val gateway: PlayerConnectionGateway,
    private val queueStore: QueueStore,
    private val priorityResolver: QueuePriorityResolver,
    private val eventBus: EventBus,
    private val serverId: String,
    private val logger: Logger = LoggerFactory.getLogger(DefaultServerTransferService::class.java),
    private val clock: Clock = Clock.systemUTC(),
) : ServerTransferService, ServerTransferCoordinator {
    private val inFlight = ConcurrentHashMap<UUID, InFlight>()
    private val processLock = Any()

    override fun requestJoin(playerUuid: UUID, serverType: String): CompletionStage<ServerTransferResult> {
        if (!config.enabled) return completed(ServerTransferResult.Failed(ServerTransferFailure.DISABLED))
        val type = serverType.trim().lowercase()
        if (!config.isEnabledType(type)) {
            return completed(ServerTransferResult.Failed(ServerTransferFailure.UNKNOWN_SERVER_TYPE))
        }
        return try {
            val current = gateway.currentServer(playerUuid)
                ?: return completed(ServerTransferResult.Failed(ServerTransferFailure.PLAYER_NOT_CONNECTED))
            val currentType = directory.typeForBackend(current)
            if (currentType == type) return completed(ServerTransferResult.AlreadyConnected(type, current))

            val now = clock.instant()
            val activeReservation = queueStore.findReservation(playerUuid, now)
            if (activeReservation != null && activeReservation.expiresAt.isAfter(now)) {
                return completed(ServerTransferResult.Failed(ServerTransferFailure.TRANSFER_IN_PROGRESS))
            }

            val selected = ServerSelection.selectLeastPopulated(type, directory.snapshot())
            if (selected != null) {
                queueStore.leave(playerUuid)?.let { left ->
                    publishQueueLeft(playerUuid, left.entry.serverType, ServerQueueLeaveReason.MOVED)
                    publishQueueSnapshot(left.entry.serverType)
                }
                connectDirect(playerUuid, type, selected)
            } else {
                completed(enqueue(playerUuid, type))
            }
        } catch (failure: Exception) {
            logger.error("Could not request server transfer for player {}", playerUuid, failure)
            completed(ServerTransferResult.Failed(ServerTransferFailure.INTERNAL_ERROR))
        }
    }

    override fun transferToServer(playerUuid: UUID, targetServerId: String): CompletionStage<ServerTransferResult> {
        if (!config.enabled) return completed(ServerTransferResult.Failed(ServerTransferFailure.DISABLED))
        val type = directory.typeForBackend(targetServerId)
            ?: return completed(ServerTransferResult.Failed(ServerTransferFailure.TARGET_NOT_FOUND))
        return try {
            val current = gateway.currentServer(playerUuid)
                ?: return completed(ServerTransferResult.Failed(ServerTransferFailure.PLAYER_NOT_CONNECTED))
            if (current == targetServerId) return completed(ServerTransferResult.AlreadyConnected(type, current))

            val now = clock.instant()
            val activeReservation = queueStore.findReservation(playerUuid, now)
            if (activeReservation != null && activeReservation.expiresAt.isAfter(now)) {
                return completed(ServerTransferResult.Failed(ServerTransferFailure.TRANSFER_IN_PROGRESS))
            }
            val backend = directory.snapshot().firstOrNull { it.name == targetServerId }
                ?: return completed(ServerTransferResult.Failed(ServerTransferFailure.TARGET_NOT_FOUND))
            if (!backend.available || !backend.acceptingPlayers) {
                return completed(ServerTransferResult.Failed(ServerTransferFailure.TARGET_NOT_FOUND))
            }
            if (!backend.hasCapacity) return completed(ServerTransferResult.Failed(ServerTransferFailure.TARGET_FULL))

            queueStore.leave(playerUuid)?.let { left ->
                publishQueueLeft(playerUuid, left.entry.serverType, ServerQueueLeaveReason.MOVED)
                publishQueueSnapshot(left.entry.serverType)
            }
            val result = CompletableFuture<ServerTransferResult>()
            gateway.connect(playerUuid, targetServerId).whenComplete { connected, failure ->
                when {
                    failure != null -> {
                        logger.warn("Direct server transfer of {} to {} failed", playerUuid, targetServerId, failure)
                        result.complete(ServerTransferResult.Failed(ServerTransferFailure.CONNECTION_REJECTED))
                    }
                    connected -> result.complete(ServerTransferResult.Connected(type, targetServerId))
                    else -> result.complete(ServerTransferResult.Failed(ServerTransferFailure.CONNECTION_REJECTED))
                }
            }
            result
        } catch (failure: Exception) {
            logger.error("Could not transfer player {} to {}", playerUuid, targetServerId, failure)
            completed(ServerTransferResult.Failed(ServerTransferFailure.INTERNAL_ERROR))
        }
    }

    override fun queueStatus(playerUuid: UUID): CompletionStage<ServerQueueStatus?> = try {
        val status = queueStore.status(playerUuid, clock.instant())
        completed(status?.let {
            ServerQueueStatus(
                serverType = it.entry.serverType,
                position = it.position,
                size = it.size,
                priority = it.entry.priority,
                reserved = it.reserved,
            )
        })
    } catch (failure: Exception) {
        logger.error("Could not read queue status for player {}", playerUuid, failure)
        CompletableFuture.failedFuture(failure)
    }

    override fun leaveQueue(playerUuid: UUID): CompletionStage<Boolean> = try {
        val left = queueStore.leave(playerUuid)
        if (left != null) {
            publishQueueLeft(playerUuid, left.entry.serverType, ServerQueueLeaveReason.LEFT)
            publishQueueSnapshot(left.entry.serverType)
            logger.info("Player {} left the {} queue", playerUuid, left.entry.serverType)
        }
        completed(left != null)
    } catch (failure: Exception) {
        logger.error("Could not remove player {} from the server queue", playerUuid, failure)
        CompletableFuture.failedFuture(failure)
    }

    override fun processQueues(serverType: String?) {
        if (!config.enabled) return
        val types = when {
            serverType == null -> config.enabledTypes()
            config.isEnabledType(serverType) -> listOf(serverType.lowercase())
            else -> return
        }
        synchronized(processLock) {
            try {
                val now = clock.instant()
                renewOrExpireInFlight(now)
                queueStore.removeExpired(now).forEach {
                    logger.info("Queue reservation {} for player {} expired", it.reservationId, it.playerUuid)
                }
                types.forEach { type -> allocateOne(type, now) }
            } catch (failure: Exception) {
                logger.error("Server queue processing failed", failure)
            }
        }
    }

    override fun playerDisconnected(playerUuid: UUID) {
        try {
            val left = queueStore.leave(playerUuid)
            if (left != null) {
                publishQueueLeft(playerUuid, left.entry.serverType, ServerQueueLeaveReason.DISCONNECTED)
                publishQueueSnapshot(left.entry.serverType)
                logger.info("Player {} was removed from the {} queue after disconnecting", playerUuid, left.entry.serverType)
            }
        } catch (failure: Exception) {
            logger.error("Could not remove disconnecting player {} from the server queue", playerUuid, failure)
        }
    }

    override fun playerConnected(playerUuid: UUID, serverId: String) {
        try {
            val left = queueStore.leave(playerUuid)
            if (left != null) {
                publishQueueLeft(playerUuid, left.entry.serverType, ServerQueueLeaveReason.TRANSFERRED)
                publishQueueSnapshot(left.entry.serverType)
                logger.info(
                    "Player {} connected to {} and was removed from the {} queue",
                    playerUuid,
                    serverId,
                    left.entry.serverType,
                )
            }
        } catch (failure: Exception) {
            logger.error("Could not clear queue state for connected player {}", playerUuid, failure)
        }
    }

    override fun close() {
        inFlight.clear()
    }

    private fun enqueue(playerUuid: UUID, serverType: String): ServerTransferResult {
        val now = clock.instant()
        val priority = try {
            priorityResolver.resolve(playerUuid)
        } catch (failure: Exception) {
            logger.warn("Could not resolve queue priority for {}; using default {}", playerUuid, config.defaultPriority, failure)
            config.defaultPriority
        }.coerceIn(0, ServerTransferConfig.MAX_PRIORITY)
        val joined = queueStore.join(playerUuid, serverType, priority, now)
        if (joined.movedFrom != null) {
            publishQueueLeft(playerUuid, joined.movedFrom, ServerQueueLeaveReason.MOVED)
            publishQueueSnapshot(joined.movedFrom)
        }
        publishQueueSnapshot(serverType)
        logger.info(
            "Player {} joined the {} queue at position {}/{} with priority {}",
            playerUuid,
            serverType,
            joined.position,
            joined.size,
            priority,
        )
        return ServerTransferResult.Queued(serverType, joined.position, joined.size)
    }

    private fun connectDirect(
        playerUuid: UUID,
        serverType: String,
        backend: BackendSnapshot,
    ): CompletionStage<ServerTransferResult> {
        val result = CompletableFuture<ServerTransferResult>()
        logger.info("Selected backend {} for player {} joining {}", backend.name, playerUuid, serverType)
        gateway.connect(playerUuid, backend.name).whenComplete { connected, failure ->
            when {
                failure != null -> {
                    logger.warn("Direct transfer of {} to {} failed; queueing instead", playerUuid, backend.name, failure)
                    result.complete(fallbackToQueue(playerUuid, serverType))
                }
                connected -> {
                    logger.info("Player {} connected to {} ({})", playerUuid, backend.name, serverType)
                    result.complete(ServerTransferResult.Connected(serverType, backend.name))
                }
                else -> {
                    logger.info("Backend {} rejected player {}; queueing instead", backend.name, playerUuid)
                    result.complete(fallbackToQueue(playerUuid, serverType))
                }
            }
        }
        return result
    }

    private fun fallbackToQueue(playerUuid: UUID, serverType: String): ServerTransferResult {
        if (!gateway.isOnline(playerUuid)) {
            return ServerTransferResult.Failed(ServerTransferFailure.PLAYER_NOT_CONNECTED)
        }
        return try {
            enqueue(playerUuid, serverType)
        } catch (failure: Exception) {
            logger.error("Could not queue player {} after a failed transfer", playerUuid, failure)
            ServerTransferResult.Failed(ServerTransferFailure.QUEUE_UNAVAILABLE)
        }
    }

    private fun allocateOne(serverType: String, now: Instant) {
        if (queueStore.size(serverType) == 0) return
        val backend = ServerSelection.selectLeastPopulated(serverType, directory.snapshot()) ?: run {
            logger.debug("No eligible backend is available for the {} queue", serverType)
            return
        }
        val reservation = queueStore.reserveNext(
            serverType,
            UUID.randomUUID(),
            now,
            config.reservationTimeoutMs,
        ) ?: return
        val inFlightEntry = InFlight(reservation, now, backend.name)
        inFlight[reservation.reservationId] = inFlightEntry
        logger.info(
            "Reserved queue slot {} for player {} on {} ({})",
            reservation.reservationId,
            reservation.playerUuid,
            backend.name,
            serverType,
        )
        publish(
            ServerQueueTransferStarted(
                metadata(ServerQueueTransferStarted.TYPE),
                reservation.playerUuid,
                serverType,
                backend.name,
                reservation.reservationId,
            ),
        )
        gateway.connect(reservation.playerUuid, backend.name).whenComplete { connected, failure ->
            completeTransfer(reservation, backend, connected, failure)
        }
    }

    private fun completeTransfer(
        reservation: QueueReservation,
        backend: BackendSnapshot,
        connected: Boolean,
        failure: Throwable?,
    ) {
        inFlight.remove(reservation.reservationId)
        try {
            if (failure == null && connected) {
                val removed = queueStore.release(reservation, removeFromQueue = true)
                publish(
                    ServerQueueTransferCompleted(
                        metadata(ServerQueueTransferCompleted.TYPE),
                        reservation.playerUuid,
                        reservation.serverType,
                        backend.name,
                        reservation.reservationId,
                    ),
                )
                if (removed) {
                    publishQueueLeft(reservation.playerUuid, reservation.serverType, ServerQueueLeaveReason.TRANSFERRED)
                    publishQueueSnapshot(reservation.serverType)
                }
                logger.info(
                    "Queued transfer completed for player {} to {} ({})",
                    reservation.playerUuid,
                    backend.name,
                    reservation.serverType,
                )
            } else {
                queueStore.release(reservation, removeFromQueue = false)
                val reason = failure?.message ?: "connection-rejected"
                publish(
                    ServerQueueTransferFailed(
                        metadata(ServerQueueTransferFailed.TYPE),
                        reservation.playerUuid,
                        reservation.serverType,
                        backend.name,
                        reservation.reservationId,
                        reason,
                    ),
                )
                publishQueueSnapshot(reservation.serverType)
                logger.warn(
                    "Queued transfer failed for player {} to {}: {}; the queue entry was retained",
                    reservation.playerUuid,
                    backend.name,
                    reason,
                )
            }
        } catch (releaseFailure: Exception) {
            logger.error("Could not finalize queue reservation {}", reservation.reservationId, releaseFailure)
        }
    }

    private fun renewOrExpireInFlight(now: Instant) {
        val maximumLifetime = Duration.ofMillis(maxOf(30_000, config.reservationTimeoutMs * 4))
        inFlight.values.toList().forEach { active ->
            if (Duration.between(active.startedAt, now) > maximumLifetime) {
                inFlight.remove(active.reservation.reservationId)
                try {
                    queueStore.release(active.reservation, removeFromQueue = false)
                } catch (failure: Exception) {
                    logger.error("Could not release expired queue reservation {}", active.reservation.reservationId, failure)
                }
                publish(
                    ServerQueueTransferFailed(
                        metadata(ServerQueueTransferFailed.TYPE),
                        active.reservation.playerUuid,
                        active.reservation.serverType,
                        active.targetServerId ?: "unknown",
                        active.reservation.reservationId,
                        "reservation-lifetime-exceeded",
                    ),
                )
                logger.warn(
                    "Queue reservation {} for player {} exceeded its maximum lifetime and was released",
                    active.reservation.reservationId,
                    active.reservation.playerUuid,
                )
            } else {
                try {
                    if (!queueStore.renew(active.reservation, now, config.reservationTimeoutMs)) {
                        inFlight.remove(active.reservation.reservationId)
                    }
                } catch (failure: Exception) {
                    logger.error("Could not renew queue reservation {}", active.reservation.reservationId, failure)
                }
            }
        }
    }

    private fun publishQueueSnapshot(serverType: String) {
        val entries = try {
            queueStore.entries(serverType)
        } catch (failure: Exception) {
            logger.error("Could not read the {} queue for action-bar updates", serverType, failure)
            return
        }
        val size = entries.size
        entries.forEachIndexed { index, entry ->
            publish(
                ServerQueueUpdated(
                    metadata(ServerQueueUpdated.TYPE),
                    entry.playerUuid,
                    serverType,
                    index + 1,
                    size,
                    entry.priority,
                ),
            )
        }
    }

    private fun publishQueueLeft(playerUuid: UUID, serverType: String, reason: ServerQueueLeaveReason) {
        publish(ServerQueueLeft(metadata(ServerQueueLeft.TYPE), playerUuid, serverType, reason))
    }

    private fun metadata(type: de.titansmp.api.event.EventType): EventMetadata =
        EventMetadata(eventType = type, sourceServerId = serverId)

    private fun publish(event: NetworkEvent) {
        try {
            eventBus.publish(event).whenComplete { _, failure ->
                if (failure != null) {
                    logger.error("Could not publish {} for player {}", event.metadata.eventType.value, playerIdOf(event), failure)
                }
            }
        } catch (failure: Exception) {
            logger.error("Could not publish server queue event {}", event.metadata.eventType.value, failure)
        }
    }

    private fun playerIdOf(event: NetworkEvent): String = when (event) {
        is ServerQueueUpdated -> event.playerUuid.toString()
        is ServerQueueLeft -> event.playerUuid.toString()
        is ServerQueueTransferStarted -> event.playerUuid.toString()
        is ServerQueueTransferCompleted -> event.playerUuid.toString()
        is ServerQueueTransferFailed -> event.playerUuid.toString()
        else -> "unknown"
    }

    private fun <T> completed(value: T): CompletionStage<T> = CompletableFuture.completedFuture(value)

    private data class InFlight(
        val reservation: QueueReservation,
        val startedAt: Instant,
        val targetServerId: String? = null,
    )
}
