package de.titansmp.paper.servertransfer

import de.titansmp.api.event.EventBus
import de.titansmp.api.event.EventListener
import de.titansmp.api.event.EventSubscription
import de.titansmp.api.servertransfer.ServerQueueLeft
import de.titansmp.api.servertransfer.ServerQueueUpdated
import de.titansmp.core.config.ServerTransferConfig
import de.titansmp.core.servertransfer.QueueActionBarFormatter
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.logging.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * Paper owns only the ActionBar presentation. Queue ordering, reservations, and transfers remain in
 * the platform-independent server-transfer module and the Velocity proxy.
 */
class ServerQueuePaperModule(
    private val plugin: JavaPlugin,
    private val eventBus: EventBus,
    private val config: ServerTransferConfig,
    private val logger: Logger,
) : Listener, AutoCloseable {
    private val subscriptions = mutableListOf<EventSubscription>()
    private val positions = ConcurrentHashMap<UUID, QueuePosition>()
    @Volatile private var task: BukkitTask? = null

    fun register() {
        plugin.server.pluginManager.registerEvents(this, plugin)
        subscriptions += eventBus.subscribe(ServerQueueUpdated.TYPE, EventListener { envelope ->
            val event = envelope.event as? ServerQueueUpdated ?: return@EventListener
            positions[event.playerUuid] = QueuePosition(event.position, event.size)
        })
        subscriptions += eventBus.subscribe(ServerQueueLeft.TYPE, EventListener { envelope ->
            val event = envelope.event as? ServerQueueLeft ?: return@EventListener
            if (positions.remove(event.playerUuid) != null) {
                plugin.server.scheduler.runTask(plugin, Runnable {
                    Bukkit.getPlayer(event.playerUuid)?.sendActionBar(Component.empty())
                })
            }
        })
        val intervalTicks = maxOf(1L, config.actionbarIntervalMs / 50L)
        task = plugin.server.scheduler.runTaskTimer(plugin, Runnable(::sendActionBars), intervalTicks, intervalTicks)
        logger.info("Server queue ActionBar updates enabled every ${config.actionbarIntervalMs}ms")
    }

    private fun sendActionBars() {
        positions.forEach { (playerUuid, position) ->
            val player = Bukkit.getPlayer(playerUuid)
            if (player != null && player.isOnline) {
                player.sendActionBar(Component.text(QueueActionBarFormatter.format(position.position, position.size)))
            }
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        positions.remove(event.player.uniqueId)
    }

    override fun close() {
        subscriptions.forEach(EventSubscription::close)
        subscriptions.clear()
        task?.cancel()
        task = null
        positions.clear()
        HandlerList.unregisterAll(this)
    }

    private data class QueuePosition(val position: Int, val size: Int)
}
