package de.titansmp.velocity.display

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.PostLoginEvent
import com.velocitypowered.api.event.player.ServerConnectedEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.player.TabListEntry
import de.titansmp.api.display.PlayerDisplayMeta
import de.titansmp.api.display.PlayerDisplayMetaChanged
import de.titansmp.api.display.PlayerDisplayService
import de.titansmp.api.event.EventBus
import de.titansmp.api.event.EventEnvelope
import de.titansmp.api.event.EventListener
import de.titansmp.api.event.EventSubscription
import de.titansmp.core.display.TAB_LIST_ORDER
import de.titansmp.core.display.TabListRow
import net.kyori.adventure.text.Component
import org.slf4j.Logger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Network-wide TAB list owned by Velocity.
 *
 * Velocity sees every player on the network, so the TAB list is complete regardless of which backend a
 * player is connected to. Each row is rendered as `prefix + name + clan suffix` from the shared
 * [PlayerDisplayService]; the proxy never resolves ranks itself. Rows are ordered by the LuckPerms rank
 * weight (higher first) and then by name. No permission node is required to appear in the list and no
 * new TAB permission is introduced.
 *
 * Client players receive the list through their own [com.velocitypowered.api.proxy.player.TabList], so
 * display names and list order are pushed to every viewer. The list is rebuilt after login, logout,
 * backend switch, and whenever another server publishes [PlayerDisplayMetaChanged].
 */
class VelocityTabListModule(
    private val plugin: Any,
    private val proxy: ProxyServer,
    private val display: PlayerDisplayService,
    private val eventBus: EventBus,
    private val logger: Logger,
) : AutoCloseable {
    private val subscription: EventSubscription =
        eventBus.subscribe(PlayerDisplayMetaChanged.TYPE, EventListener(::onMetaChanged))

    @Subscribe
    fun onPostLogin(event: PostLoginEvent) {
        refresh()
        // A second pass after the player reaches a backend, in case rank data was still loading.
        proxy.scheduler.buildTask(plugin, Runnable(::refresh)).delay(1, TimeUnit.SECONDS).schedule()
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        refresh()
    }

    @Subscribe
    fun onServerConnected(event: ServerConnectedEvent) {
        refresh()
    }

    private fun onMetaChanged(envelope: EventEnvelope) {
        if (envelope.event !is PlayerDisplayMetaChanged) return
        refresh()
    }

    private fun refresh() {
        val players = proxy.allPlayers.toList()
        if (players.isEmpty()) return
        val stages = players.associateWith { display.displayMeta(it.uniqueId) }
        val futures = stages.values.map { it.toCompletableFuture() }
        CompletableFuture.allOf(*futures.toTypedArray()).whenComplete { _, _ -> schedule { apply(players, stages) } }
    }

    private fun apply(players: List<Player>, stages: Map<Player, java.util.concurrent.CompletionStage<PlayerDisplayMeta>>) {
        val online = proxy.allPlayers.toList()
        val byId = online.associateBy { it.uniqueId }
        val rows = online.map { player ->
            val meta = stages[player]?.toCompletableFuture()?.getNow(null)
                ?: display.cachedMeta(player.uniqueId)
                ?: PlayerDisplayMeta(player.uniqueId)
            TabListRow(player.uniqueId, player.username, meta)
        }.sortedWith(TAB_LIST_ORDER)

        online.forEach { viewer ->
            val tabList = viewer.tabList
            tabList.entries.filter { byId[it.profile.id] == null }.forEach { tabList.removeEntry(it.profile.id) }
            rows.forEachIndexed { index, row ->
                val order = rows.size - index // higher list order ranks first
                val text = Component.text(row.displayText)
                val existing = tabList.getEntry(row.playerUuid)
                if (existing.isPresent) {
                    existing.get().setDisplayName(text)
                    existing.get().setListOrder(order)
                } else {
                    val profile = byId[row.playerUuid]?.gameProfile ?: return@forEachIndexed
                    tabList.addEntry(
                        TabListEntry.builder()
                            .tabList(tabList)
                            .profile(profile)
                            .displayName(text)
                            .listed(true)
                            .listOrder(order)
                            .build(),
                    )
                }
            }
        }
    }

    private fun schedule(action: () -> Unit) {
        proxy.scheduler.buildTask(plugin, Runnable(action)).schedule()
    }

    override fun close() {
        subscription.close()
        proxy.eventManager.unregisterListener(plugin, this)
    }
}
