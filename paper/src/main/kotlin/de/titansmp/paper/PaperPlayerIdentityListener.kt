package de.titansmp.paper

import de.titansmp.api.event.EventBus
import de.titansmp.api.event.EventMetadata
import de.titansmp.api.event.EventType
import de.titansmp.api.player.PlayerService
import de.titansmp.api.player.event.PlayerConnected
import de.titansmp.api.player.event.PlayerDisconnected
import de.titansmp.api.player.event.PlayerServerChanged
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.logging.Logger

/** Performs blocking persistence away from Paper's server thread and keeps only event data. */
class PaperPlayerIdentityListener(
    private val runtime: CompletableFuture<PaperPlayerIdentityRuntime>,
    private val logger: Logger,
    private val onMain: ((() -> Unit) -> Unit),
    private val persistence: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "titansmp-player-identity").apply { isDaemon = true }
    },
) : Listener, AutoCloseable {
    @Volatile var onPlayerOnline: (UUID) -> Unit = {}
    @Volatile var onPlayerOnlineFailure: (UUID, Throwable) -> Unit = { _, _ -> }
    @Volatile var beforePlayerOffline: (org.bukkit.entity.Player) -> CompletionStage<Unit> = { CompletableFuture.completedFuture(Unit) }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val uuid = event.player.uniqueId
        val username = event.player.name
        persistence.execute {
            runCatching {
                val dependencies = runtime.get()
                val playerService = dependencies.playerService
                val serverId = dependencies.serverId
                val previous = playerService.createOrLoad(uuid, username)
                val previousServer = previous.currentServer
                playerService.setOnline(uuid, serverId)
                onMain { onPlayerOnline(uuid) }
                val event = if (previous.online && previousServer != null && previousServer != serverId) {
                    PlayerServerChanged(
                        EventMetadata(eventType = PlayerServerChanged.TYPE, sourceServerId = serverId),
                        uuid,
                        previousServer,
                        serverId,
                    )
                } else {
                    PlayerConnected(
                        EventMetadata(eventType = PlayerConnected.TYPE, sourceServerId = serverId),
                        uuid,
                        username,
                        serverId,
                    )
                }
                publish(dependencies.eventBus, event.metadata.eventType, event)
            }.onFailure {
                logger.severe("Could not update player identity for $uuid: ${it.message}")
                onMain { onPlayerOnlineFailure(uuid, it) }
            }
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        val saving = try { beforePlayerOffline(event.player) } catch (failure: Throwable) { CompletableFuture.failedFuture(failure) }
        saving.whenComplete { _, saveFailure ->
            if (saveFailure != null) {
                logger.severe("Player state save failed for $uuid; leaving presence online: ${saveFailure.cause?.message ?: saveFailure.message}")
                return@whenComplete
            }
            persistence.execute {
                runCatching {
                    val dependencies = runtime.get()
                    if (dependencies.playerService.setOffline(uuid, dependencies.serverId)) {
                        publish(dependencies.eventBus, PlayerDisconnected.TYPE,
                            PlayerDisconnected(EventMetadata(eventType = PlayerDisconnected.TYPE, sourceServerId = dependencies.serverId), uuid, dependencies.serverId))
                    }
                }.onFailure { logger.severe("Could not update player identity for $uuid: ${it.message}") }
            }
        }
    }

    private fun publish(eventBus: EventBus, eventType: EventType, event: de.titansmp.api.event.NetworkEvent) {
        eventBus.publish(event).whenComplete { _, failure ->
            if (failure != null) logger.severe("Could not publish player presence event ${eventType.value}: ${failure.message}")
        }
    }

    override fun close() {
        stopAccepting()
        awaitTermination()
    }

    fun stopAccepting() = persistence.shutdown()

    fun awaitTermination() {
        var interrupted = false
        try {
            while (!persistence.isTerminated) {
                try {
                    if (!persistence.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) persistence.shutdownNow()
                } catch (_: InterruptedException) {
                    interrupted = true
                    persistence.shutdownNow()
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}

data class PaperPlayerIdentityRuntime(
    val playerService: PlayerService,
    val eventBus: EventBus,
    val serverId: String,
)
