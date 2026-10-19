package de.titansmp.paper.display

import de.titansmp.api.display.PlayerDisplayMeta
import de.titansmp.api.display.PlayerDisplayMetaChanged
import de.titansmp.api.display.PlayerDisplayService
import de.titansmp.api.event.EventBus
import de.titansmp.api.event.EventEnvelope
import de.titansmp.api.event.EventListener
import de.titansmp.api.event.EventMetadata
import de.titansmp.api.event.EventSubscription
import de.titansmp.api.event.EventType
import de.titansmp.api.event.NetworkEvent
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

class PlayerDisplayPaperModuleTest {
    private lateinit var server: ServerMock
    private lateinit var plugin: JavaPlugin
    private lateinit var display: FakeDisplayService
    private lateinit var bus: RecordingBus
    private lateinit var module: PlayerDisplayPaperModule

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        plugin = MockBukkit.load(TestPaperPlugin::class.java)
        display = FakeDisplayService()
        bus = RecordingBus()
        module = PlayerDisplayPaperModule(plugin, display, bus)
        module.register()
    }

    @AfterEach
    fun tearDown() {
        module.close()
        MockBukkit.unmock()
    }

    @Test
    fun `join applies prefix and never the clan suffix to the nametag team`() {
        display.meta = PlayerDisplayMeta(UUID.randomUUID(), "[Admin]", "[TITAN]", 100)
        val player = server.addPlayer("Henry")
        display.meta = display.meta.copy(playerUuid = player.uniqueId)

        Bukkit.getPluginManager().callEvent(PlayerJoinEvent(player, Component.text("joined")))
        server.scheduler.performTicks(1)

        val team = scoreboard().getTeam(PlayerDisplayPaperModule.teamName(player.uniqueId))
        assertNotNull(team)
        assertEquals(true, team!!.hasEntry("Henry"))
        assertEquals(Component.text("[Admin] "), team.prefix())
        assertFalse(team.prefix().toString().contains("[TITAN]"))
    }

    @Test
    fun `a rank change updates the existing team prefix`() {
        val player = server.addPlayer("Henry")
        display.meta = PlayerDisplayMeta(player.uniqueId, "[Member]", null, 1)
        Bukkit.getPluginManager().callEvent(PlayerJoinEvent(player, Component.text("joined")))
        server.scheduler.performTicks(1)
        val teamName = PlayerDisplayPaperModule.teamName(player.uniqueId)

        bus.emit(
            PlayerDisplayMetaChanged(
                EventMetadata(eventType = PlayerDisplayMetaChanged.TYPE, sourceServerId = "smp-02"),
                player.uniqueId,
                PlayerDisplayMeta(player.uniqueId, "[Moderator]", null, 50),
            ),
        )
        server.scheduler.performTicks(1)

        val team = scoreboard().getTeam(teamName)
        assertNotNull(team)
        assertEquals(Component.text("[Moderator] "), team!!.prefix())
        assertEquals(1, scoreboard().teams.count { it.name == teamName })
    }

    @Test
    @Suppress("DEPRECATION")
    fun `quit removes the nametag team`() {
        val player = server.addPlayer("Henry")
        display.meta = PlayerDisplayMeta(player.uniqueId, "[Admin]", null, 100)
        Bukkit.getPluginManager().callEvent(PlayerJoinEvent(player, Component.text("joined")))
        server.scheduler.performTicks(1)
        assertNotNull(scoreboard().getTeam(PlayerDisplayPaperModule.teamName(player.uniqueId)))

        Bukkit.getPluginManager().callEvent(PlayerQuitEvent(player, "bye", PlayerQuitEvent.QuitReason.DISCONNECTED))
        server.scheduler.performTicks(1)

        assertNull(scoreboard().getTeam(PlayerDisplayPaperModule.teamName(player.uniqueId)))
    }

    private fun scoreboard() = Bukkit.getScoreboardManager().mainScoreboard

    open class TestPaperPlugin : JavaPlugin()

    private class FakeDisplayService : PlayerDisplayService {
        var meta: PlayerDisplayMeta = PlayerDisplayMeta(UUID.randomUUID(), "", null, 0)

        override fun displayMeta(playerUuid: UUID): CompletionStage<PlayerDisplayMeta> =
            CompletableFuture.completedFuture(meta.copy(playerUuid = playerUuid))

        override fun cachedMeta(playerUuid: UUID): PlayerDisplayMeta? = meta.copy(playerUuid = playerUuid)
        override fun refresh(playerUuid: UUID): CompletionStage<PlayerDisplayMeta> = displayMeta(playerUuid)
        override fun invalidate(playerUuid: UUID) = Unit
        override fun cachedPlayers(): Set<UUID> = emptySet()
    }

    private class RecordingBus : EventBus {
        private val listeners = mutableMapOf<EventType, MutableList<EventListener>>()

        override fun publish(event: NetworkEvent): CompletionStage<Unit> = CompletableFuture.completedFuture(Unit)

        override fun subscribe(eventType: EventType, listener: EventListener): EventSubscription {
            listeners.getOrPut(eventType) { mutableListOf() } += listener
            return EventSubscription { listeners[eventType]?.remove(listener) }
        }

        fun emit(event: NetworkEvent) {
            listeners[event.metadata.eventType]?.toList()?.forEach { it.onEvent(EventEnvelope(event)) }
        }

        override fun close() = Unit
    }
}
