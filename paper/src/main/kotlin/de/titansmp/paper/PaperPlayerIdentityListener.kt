package de.titansmp.paper

import de.titansmp.api.player.PlayerService
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.logging.Logger

/** Performs blocking persistence away from Paper's server thread and keeps only event data. */
class PaperPlayerIdentityListener(
    private val playerService: PlayerService,
    private val serverId: String,
    private val logger: Logger,
    private val persistence: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "titansmp-player-identity").apply { isDaemon = true }
    },
) : Listener, AutoCloseable {
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val uuid = event.player.uniqueId
        val username = event.player.name
        persistence.execute {
            runCatching {
                playerService.createOrLoad(uuid, username)
                playerService.setOnline(uuid, serverId)
            }.onFailure { logger.severe("Could not update player identity for $uuid: ${it.message}") }
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val uuid: UUID = event.player.uniqueId
        persistence.execute {
            runCatching { playerService.setOffline(uuid, serverId) }
                .onFailure { logger.severe("Could not update player identity for $uuid: ${it.message}") }
        }
    }

    override fun close() {
        persistence.shutdown()
        try {
            if (!persistence.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) persistence.shutdownNow()
        } catch (_: InterruptedException) {
            persistence.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
}
