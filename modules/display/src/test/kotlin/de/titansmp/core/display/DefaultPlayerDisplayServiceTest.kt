package de.titansmp.core.display

import de.titansmp.api.display.DisplayProvider
import de.titansmp.api.display.DisplaySuffixProvider
import de.titansmp.api.display.PlayerDisplayMeta
import de.titansmp.api.display.PlayerDisplayMetaChanged
import de.titansmp.api.event.EventBus
import de.titansmp.api.event.EventEnvelope
import de.titansmp.api.event.EventListener
import de.titansmp.api.event.EventMetadata
import de.titansmp.api.event.EventSubscription
import de.titansmp.api.event.EventType
import de.titansmp.api.event.NetworkEvent
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertIs

class DefaultPlayerDisplayServiceTest {
    private val player = UUID.randomUUID()

    @Test
    fun `displayMeta caches the resolved value`() {
        var calls = 0
        val service = DefaultPlayerDisplayService(DisplayProvider { uuid ->
            calls++
            CompletableFuture.completedFuture(PlayerDisplayMeta(uuid, "[Admin]", null, 50))
        })
        val first = service.displayMeta(player).toCompletableFuture().join()
        val second = service.displayMeta(player).toCompletableFuture().join()
        assertEquals(1, calls)
        assertEquals(first, second)
        assertEquals(first, service.cachedMeta(player))
    }

    @Test
    fun `refresh publishes a change event only when the meta changes`() {
        val bus = TestEventBus()
        var meta = PlayerDisplayMeta(player, "[Member]", null, 1)
        val service = DefaultPlayerDisplayService(DisplayProvider { CompletableFuture.completedFuture(meta) }, eventBus = bus, serverId = "smp-01")
        service.displayMeta(player).toCompletableFuture().join()

        service.refresh(player).toCompletableFuture().join()
        assertEquals(0, bus.published.size)

        meta = PlayerDisplayMeta(player, "[Moderator]", null, 50)
        service.refresh(player).toCompletableFuture().join()
        assertEquals(1, bus.published.size)
        val changed = assertIs<PlayerDisplayMetaChanged>(bus.published.single())
        assertEquals(player, changed.playerUuid)
        assertEquals("[Moderator]", changed.displayMeta.prefix)
        assertEquals("smp-01", changed.metadata.sourceServerId)
    }

    @Test
    fun `remote change event updates the local cache`() {
        val bus = TestEventBus()
        val service = DefaultPlayerDisplayService(DisplayProvider { CompletableFuture.completedFuture(PlayerDisplayMeta(it)) }, eventBus = bus, serverId = "smp-01")
        val remote = PlayerDisplayMeta(player, "[Builder]", null, 25)
        bus.emit(PlayerDisplayMetaChanged(EventMetadata(eventType = PlayerDisplayMetaChanged.TYPE, sourceServerId = "smp-02"), player, remote))
        assertEquals(remote, service.cachedMeta(player))
    }

    @Test
    fun `own change events are ignored to avoid a publish loop`() {
        val bus = TestEventBus()
        val service = DefaultPlayerDisplayService(DisplayProvider { CompletableFuture.completedFuture(PlayerDisplayMeta(it)) }, eventBus = bus, serverId = "smp-01")
        bus.emit(PlayerDisplayMetaChanged(EventMetadata(eventType = PlayerDisplayMetaChanged.TYPE, sourceServerId = "smp-01"), player, PlayerDisplayMeta(player, "[Admin]")))
        assertNull(service.cachedMeta(player))
    }

    @Test
    fun `suffix stays empty by default and can later be filled by a clan suffix provider`() {
        val withoutClan = DefaultPlayerDisplayService(DisplayProvider { CompletableFuture.completedFuture(PlayerDisplayMeta(it, "[Member]", null, 1)) })
        assertNull(withoutClan.displayMeta(player).toCompletableFuture().join().suffix)

        val withClan = DefaultPlayerDisplayService(
            DisplayProvider { CompletableFuture.completedFuture(PlayerDisplayMeta(it, "[Member]", null, 1)) },
            suffixProviders = listOf(DisplaySuffixProvider { CompletableFuture.completedFuture("[TITAN]") }),
        )
        assertEquals("[TITAN]", withClan.displayMeta(player).toCompletableFuture().join().suffix)
    }

    @Test
    fun `blank clan suffix is normalised to the previous value`() {
        val service = DefaultPlayerDisplayService(
            DisplayProvider { CompletableFuture.completedFuture(PlayerDisplayMeta(it, "[Member]", null, 1)) },
            suffixProviders = listOf(DisplaySuffixProvider { CompletableFuture.completedFuture("  ") }),
        )
        assertNull(service.displayMeta(player).toCompletableFuture().join().suffix)
    }

    private class TestEventBus : EventBus {
        val published = mutableListOf<NetworkEvent>()
        private val listeners = mutableMapOf<EventType, MutableList<EventListener>>()

        override fun publish(event: NetworkEvent): CompletionStage<Unit> {
            published += event
            return CompletableFuture.completedFuture(Unit)
        }

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
