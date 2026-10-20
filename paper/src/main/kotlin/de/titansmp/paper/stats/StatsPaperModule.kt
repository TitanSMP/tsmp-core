package de.titansmp.paper.stats

import de.titansmp.api.event.EventBus
import de.titansmp.api.event.EventListener
import de.titansmp.api.event.EventSubscription
import de.titansmp.api.player.event.PlayerConnected
import de.titansmp.api.stats.StatType
import de.titansmp.core.stats.FractionalAccumulator
import de.titansmp.core.stats.PlaytimeTracker
import de.titansmp.core.stats.StatsAggregator
import de.titansmp.core.stats.StatsTrackingPolicy
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.util.logging.Logger

/**
 * Paper event source for the network-wide statistics system.
 *
 * Every Bukkit listener stays on the server thread and only increments in-memory counters. MariaDB
 * is written exclusively by the periodic flush task on a background thread, and playtime is measured
 * per local session by [PlaytimeTracker], so a server switch never double-counts time and no block,
 * step, or hit triggers its own database write.
 *
 * Network-wide joins reuse the existing [PlayerConnected] presence event instead of the raw Bukkit
 * join, so a lobby -> SMP -> lobby server switch never increases the join statistic. Because every
 * server receives that event, the join is counted only on the server that published it.
 *
 * Which statistics are collected is decided by [policy], which is driven entirely by configuration
 * and this server's configured identity type. No server name or server-type name is hardcoded here.
 */
class StatsPaperModule(
    private val plugin: JavaPlugin,
    private val policy: StatsTrackingPolicy,
    private val aggregator: StatsAggregator,
    private val playtime: PlaytimeTracker,
    private val distance: FractionalAccumulator,
    private val damage: FractionalAccumulator,
    private val eventBus: EventBus,
    private val serverId: String,
    private val flushIntervalMs: Long,
    private val logger: Logger = plugin.logger,
) : Listener, AutoCloseable {
    private val subscriptions = mutableListOf<EventSubscription>()
    @Volatile private var flushTask: BukkitTask? = null
    @Volatile private var accepting = true

    fun register() {
        plugin.server.pluginManager.registerEvents(this, plugin)
        if (policy.tracks(StatType.JOINS)) {
            // PlayerConnected is published by exactly the server the player joined, so filtering on
            // our own server ID counts the network join once and ignores intra-network transfers.
            subscriptions += eventBus.subscribe(PlayerConnected.TYPE, EventListener { envelope ->
                val event = envelope.event as? PlayerConnected ?: return@EventListener
                if (event.serverId != serverId) return@EventListener
                aggregator.record(event.playerId, StatType.JOINS, 1)
            })
        }
        val intervalTicks = maxOf(1L, flushIntervalMs / 50L)
        flushTask = plugin.server.scheduler.runTaskTimerAsynchronously(
            plugin,
            Runnable(::flush),
            intervalTicks,
            intervalTicks,
        )
        logger.info("TitanSMP statistics tracking enabled; flush interval ${flushIntervalMs}ms")
    }

    /** Drains the fractional accumulators into the integer aggregator and persists. */
    private fun flush() {
        if (!accepting) return
        distance.drain().forEach { (playerUuid, values) -> values.forEach { (type, value) -> aggregator.record(playerUuid, type, value) } }
        damage.drain().forEach { (playerUuid, values) -> values.forEach { (type, value) -> aggregator.record(playerUuid, type, value) } }
        aggregator.flush()
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        playtime.start(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        if (!accepting) return
        playtime.stop(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (!policy.tracks(StatType.BLOCKS_MINED)) return
        aggregator.record(event.player.uniqueId, StatType.BLOCKS_MINED, 1)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        if (!event.hasChangedPosition()) return
        val player = event.player
        val distanceMoved = horizontalDistance(event.from.x, event.from.z, event.to.x, event.to.z)
        if (distanceMoved <= 0.0) return
        val statType = if (player.isGliding) StatType.BLOCKS_ELYTRA else StatType.BLOCKS_WALKED
        if (!policy.tracks(statType)) return
        distance.record(player.uniqueId, statType, distanceMoved)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDamageTaken(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (!policy.tracks(StatType.DAMAGE_TAKEN)) return
        // Paper damage is in half-hearts; the statistic is stored in whole health points.
        damage.record(player.uniqueId, StatType.DAMAGE_TAKEN, event.finalDamage / 2.0)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDamageDealt(event: EntityDamageByEntityEvent) {
        val attacker = event.damager as? Player ?: return
        if (!policy.tracks(StatType.DAMAGE_DEALT)) return
        damage.record(attacker.uniqueId, StatType.DAMAGE_DEALT, event.finalDamage / 2.0)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerDeath(event: PlayerDeathEvent) {
        if (policy.tracks(StatType.DEATHS)) {
            aggregator.record(event.entity.uniqueId, StatType.DEATHS, 1)
        }
        val killer = event.entity.killer
        if (killer != null && policy.tracks(StatType.PLAYERS_KILLED)) {
            aggregator.record(killer.uniqueId, StatType.PLAYERS_KILLED, 1)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntityDeath(event: EntityDeathEvent) {
        val entity = event.entity
        // A player kill is counted as PLAYERS_KILLED, never also as a mob kill.
        if (entity is Player) return
        if (!policy.tracks(StatType.MOBS_KILLED)) return
        val killer = entity.killer ?: return
        aggregator.record(killer.uniqueId, StatType.MOBS_KILLED, 1)
    }

    /** Horizontal (X/Z) distance, matching vanilla walk/elytra distance semantics. */
    private fun horizontalDistance(fromX: Double, fromZ: Double, toX: Double, toZ: Double): Double {
        val dx = toX - fromX
        val dz = toZ - fromZ
        return kotlin.math.sqrt(dx * dx + dz * dz)
    }

    /**
     * Stops the timer, drains every playtime session and counter, and flushes once more. Called from
     * the plugin's shutdown path so an abrupt stop loses as little as technically possible.
     */
    fun flushAndClose() {
        accepting = false
        flushTask?.cancel()
        flushTask = null
        playtime.stopAll()
        flush()
        aggregator.flush()
    }

    override fun close() {
        subscriptions.forEach(EventSubscription::close)
        subscriptions.clear()
        HandlerList.unregisterAll(this)
    }
}
