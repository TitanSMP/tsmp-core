package de.titansmp.paper.display

import de.titansmp.api.display.PlayerDisplayMeta
import de.titansmp.api.display.PlayerDisplayMetaChanged
import de.titansmp.api.display.PlayerDisplayService
import de.titansmp.api.event.EventBus
import de.titansmp.api.event.EventEnvelope
import de.titansmp.api.event.EventListener
import de.titansmp.api.event.EventSubscription
import de.titansmp.core.display.PlayerDisplayFormat
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Paper nametag adapter.
 *
 * The nametag rule is `prefix + name` only; the clan suffix is never shown here, only in the Velocity
 * TAB list. The prefix comes from the shared [PlayerDisplayService], so Paper never resolves ranks
 * itself. It uses the stable Bukkit scoreboard-team API: one team per player UUID carries the prefix,
 * and the player's name is the team entry. No packets, NMS, TAB plugin, or PlaceholderAPI are used.
 *
 * The prefix is applied on the main thread; name changes and meta changes are pushed through the same
 * team. Teams are removed on quit and when the module closes.
 */
class PlayerDisplayPaperModule(
    private val plugin: JavaPlugin,
    private val display: PlayerDisplayService,
    private val eventBus: EventBus,
) : Listener, AutoCloseable {
    private val subscriptions = mutableListOf<EventSubscription>()
    private val teamNames = ConcurrentHashMap<UUID, String>()

    fun register() {
        Bukkit.getPluginManager().registerEvents(this, plugin)
        subscriptions += eventBus.subscribe(PlayerDisplayMetaChanged.TYPE, EventListener(::onMetaChanged))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        display.displayMeta(player.uniqueId).whenComplete { meta, failure ->
            if (failure != null || meta == null || !player.isOnline) return@whenComplete
            onMainThread { apply(player, meta) }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        removeTeam(event.player.uniqueId)
    }

    private fun onMetaChanged(envelope: EventEnvelope) {
        val event = envelope.event as? PlayerDisplayMetaChanged ?: return
        val player = Bukkit.getPlayer(event.playerUuid) ?: return
        onMainThread { apply(player, event.displayMeta) }
    }

    private fun apply(player: Player, meta: PlayerDisplayMeta) {
        val scoreboard = Bukkit.getScoreboardManager().mainScoreboard
        val teamName = teamName(player.uniqueId)
        val team = scoreboard.getTeam(teamName) ?: scoreboard.registerNewTeam(teamName)
        if (!team.hasEntry(player.name)) team.addEntry(player.name)
        team.prefix(Component.text(PlayerDisplayFormat.nameTagPrefix(meta.prefix)))
        teamNames[player.uniqueId] = teamName
    }

    private fun removeTeam(playerUuid: UUID) {
        val teamName = teamNames.remove(playerUuid) ?: teamName(playerUuid)
        onMainThread { Bukkit.getScoreboardManager().mainScoreboard.getTeam(teamName)?.unregister() }
    }

    private fun onMainThread(action: () -> Unit) {
        if (Bukkit.isPrimaryThread()) action() else Bukkit.getScheduler().runTask(plugin, Runnable(action))
    }

    override fun close() {
        subscriptions.forEach(EventSubscription::close)
        subscriptions.clear()
        val names = teamNames.values.toSet()
        teamNames.clear()
        onMainThread {
            val scoreboard = Bukkit.getScoreboardManager().mainScoreboard
            names.forEach { name -> scoreboard.getTeam(name)?.unregister() }
        }
    }

    companion object {
        /** Deterministic team name derived from the player UUID (`tsmp` + 12 hex characters, 16 total). */
        fun teamName(playerUuid: UUID): String = "tsmp" + playerUuid.toString().replace("-", "").take(12)
    }
}
