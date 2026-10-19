package de.titansmp.chat

import de.titansmp.api.chat.ChatException
import de.titansmp.api.chat.ChatMessage
import de.titansmp.api.chat.ChatMessageSource
import de.titansmp.api.chat.NetworkChatMessage
import de.titansmp.api.event.EventBus
import de.titansmp.api.event.EventEnvelope
import de.titansmp.api.event.EventListener
import de.titansmp.api.event.EventSubscription
import de.titansmp.api.event.EventType
import de.titansmp.api.event.NetworkEvent
import de.titansmp.api.moderation.ActivePunishment
import de.titansmp.api.moderation.ModerationActor
import de.titansmp.api.moderation.ModerationPermissionService
import de.titansmp.api.moderation.ModerationService
import de.titansmp.api.moderation.ModerationTarget
import de.titansmp.api.moderation.PunishmentType
import de.titansmp.core.config.ChatConfig
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChatServiceTest {
    private val clock = MutableClock(Instant.parse("2026-10-19T20:00:00Z"))
    private val sender = UUID.randomUUID()
    private val bus = RecordingBus()
    private var allowed = true
    private var muted = false
    private val permissions = ModerationPermissionService { _, node ->
        CompletableFuture.completedFuture(node == "titansmp.chat.use" && allowed)
    }
    private val moderation = object : ModerationService {
        override fun ban(actor: ModerationActor, target: ModerationTarget, reason: String, serverId: String): CompletionStage<ActivePunishment> = error("unused")
        override fun unban(actor: ModerationActor, target: ModerationTarget, reason: String?, serverId: String): CompletionStage<Unit> = error("unused")
        override fun mute(actor: ModerationActor, target: ModerationTarget, duration: Duration?, reason: String, serverId: String): CompletionStage<ActivePunishment> = error("unused")
        override fun unmute(actor: ModerationActor, target: ModerationTarget, reason: String?, serverId: String): CompletionStage<Unit> = error("unused")
        override fun kick(actor: ModerationActor, target: ModerationTarget, reason: String, serverId: String): CompletionStage<Unit> = error("unused")
        override fun checkPunishment(targetId: UUID, type: PunishmentType): CompletionStage<ActivePunishment?> = CompletableFuture.completedFuture(null)
        override fun isMuted(targetId: UUID): CompletionStage<Boolean> = CompletableFuture.completedFuture(muted)
    }

    private fun service(config: ChatConfig = ChatConfig(messagesPerWindow = 2, windowMs = 3_000)) =
        DefaultChatService(bus, moderation, permissions, config, clock)

    @Test fun `valid minecraft message publishes event with configured server and UTC instant`() {
        val message = service().send(sender, "Henry", "Hello", "smp-01").toCompletableFuture().join()
        assertEquals(sender, message.senderUuid)
        assertEquals("smp-01", message.serverId)
        assertEquals(Instant.parse("2026-10-19T20:00:00Z"), message.timestamp)
        assertEquals(ChatMessageSource.MINECRAFT, message.source)
        assertIs<NetworkChatMessage>(bus.events.single())
    }

    @Test fun `empty whitespace and excessive messages are rejected`() {
        val chat = service(ChatConfig(maxMessageLength = 4))
        listOf("", "   ").forEach { text ->
            val failure = assertFailsWith<java.util.concurrent.CompletionException> { chat.send(sender, "Henry", text, "smp-01").toCompletableFuture().join() }
            assertEquals(ChatException.Code.EMPTY_MESSAGE, (failure.cause as ChatException).code)
        }
        val long = assertFailsWith<java.util.concurrent.CompletionException> { chat.send(sender, "Henry", "hello", "smp-01").toCompletableFuture().join() }
        assertEquals(ChatException.Code.MESSAGE_TOO_LONG, (long.cause as ChatException).code)
        assertTrue(bus.events.isEmpty())
    }

    @Test fun `mute and missing use permission block send`() {
        muted = true
        var failure = assertFailsWith<java.util.concurrent.CompletionException> { service().send(sender, "Henry", "Hello", "smp-01").toCompletableFuture().join() }
        assertEquals(ChatException.Code.MUTED, (failure.cause as ChatException).code)
        muted = false; allowed = false
        failure = assertFailsWith { service().send(sender, "Henry", "Hello", "smp-01").toCompletableFuture().join() }
        assertEquals(ChatException.Code.NO_PERMISSION, (failure.cause as ChatException).code)
        assertTrue(bus.events.isEmpty())
    }

    @Test fun `rate limit blocks excess messages then resets after window`() {
        val chat = service()
        chat.send(sender, "Henry", "One", "smp-01").toCompletableFuture().join()
        chat.send(sender, "Henry", "Two", "smp-01").toCompletableFuture().join()
        val blocked = assertFailsWith<java.util.concurrent.CompletionException> { chat.send(sender, "Henry", "Three", "smp-01").toCompletableFuture().join() }
        assertEquals(ChatException.Code.RATE_LIMITED, (blocked.cause as ChatException).code)
        clock.advance(Duration.ofSeconds(3))
        chat.send(sender, "Henry", "Again", "smp-01").toCompletableFuture().join()
        assertEquals(3, bus.events.size)
    }

    @Test fun `failed redis publish does not create local success`() {
        bus.failure = IllegalStateException("Redis unavailable")
        val failure = assertFailsWith<java.util.concurrent.CompletionException> { service().send(sender, "Henry", "Hello", "smp-01").toCompletableFuture().join() }
        assertEquals(ChatException.Code.PUBLISH_FAILED, (failure.cause as ChatException).code)
    }

    @Test fun `system source can omit player uuid and renderer stays configurable`() {
        val message = ChatMessage(UUID.randomUUID(), null, "System", "Maintenance soon", "proxy-01", clock.instant(), ChatMessageSource.SYSTEM)
        service().broadcast(message).toCompletableFuture().join()
        assertEquals("[System] Maintenance soon", de.titansmp.api.chat.ChatRenderer { "[System] ${it.message}" }.render(message))
    }

    private class RecordingBus : EventBus {
        val events = mutableListOf<NetworkEvent>()
        var failure: Throwable? = null
        override fun publish(event: NetworkEvent): CompletionStage<Unit> {
            failure?.let { return CompletableFuture.failedFuture(it) }
            events += event
            return CompletableFuture.completedFuture(Unit)
        }
        override fun subscribe(eventType: EventType, listener: EventListener): EventSubscription = EventSubscription {}
        override fun close() = Unit
    }

    internal class MutableClock(private var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = current
        fun advance(duration: Duration) { current = current.plus(duration) }
    }
}

class ChatRateLimiterTest {
    @Test fun `rate limiter can be disabled`() {
        val limiter = ChatRateLimiter(1, 1000, enabled = false)
        val player = UUID.randomUUID()
        assertTrue(limiter.tryAcquire(player))
        assertTrue(limiter.tryAcquire(player))
    }
}

class RecentChatMessageCacheTest {
    @Test fun `message id is unique bounded and expires`() {
        val clock = ChatServiceTest.MutableClock(Instant.parse("2026-10-19T20:00:00Z"))
        val cache = RecentChatMessageCache(capacity = 1, ttl = Duration.ofSeconds(5), clock = clock)
        val first = UUID.randomUUID(); val second = UUID.randomUUID()
        assertTrue(cache.markIfNew(first))
        assertFalse(cache.markIfNew(first))
        assertTrue(cache.markIfNew(second))
        assertTrue(cache.markIfNew(first)) // oldest entry evicted to respect the size bound
        assertFalse(cache.markIfNew(first))
        clock.advance(Duration.ofSeconds(6))
        assertTrue(cache.markIfNew(first))
    }
}
