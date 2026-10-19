package de.titansmp.core.event

import de.titansmp.api.event.EventEnvelope
import de.titansmp.api.event.EventListener
import de.titansmp.api.event.EventType
import de.titansmp.api.player.event.PlayerConnected
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RedisEventBusTest {
    @Test
    fun `publishes to multiple listeners and isolates listener failures`() {
        val transport = InMemoryTransport()
        val bus = RedisEventBus("test-network", transport)
        val delivered = CountDownLatch(2)
        val ids = CopyOnWriteArrayList<UUID>()
        bus.subscribe(PlayerConnected.TYPE, EventListener { error("listener failure") })
        repeat(2) {
            bus.subscribe(PlayerConnected.TYPE, EventListener { envelope ->
                ids += (envelope.event as PlayerConnected).playerId
                delivered.countDown()
            })
        }
        val playerId = UUID.randomUUID()

        bus.publish(connected(playerId)).toCompletableFuture().get(2, TimeUnit.SECONDS)

        assertTrue(delivered.await(2, TimeUnit.SECONDS))
        assertEquals(listOf(playerId, playerId), ids.sortedBy { it.toString() })
        bus.close()
    }

    @Test
    fun `subscription can be removed and shutdown prevents processing`() {
        val transport = InMemoryTransport()
        val bus = RedisEventBus("test-network", transport)
        var count = 0
        val subscription = bus.subscribe(PlayerConnected.TYPE) { count++ }
        subscription.close()
        bus.publish(connected(UUID.randomUUID())).toCompletableFuture().get(2, TimeUnit.SECONDS)
        Thread.sleep(50)
        assertEquals(0, count)

        bus.close()
        assertFailsWith<IllegalStateException> { bus.publish(connected(UUID.randomUUID())) }
        assertFailsWith<IllegalStateException> { bus.subscribe(EventType("player.connected"), EventListener {}) }
    }

    @Test
    fun `channel is isolated by validated network namespace`() {
        val bus = RedisEventBus("survival-1", InMemoryTransport())
        assertEquals("titansmp:survival-1:events:v1", bus.channel)
        bus.close()
        assertFailsWith<IllegalArgumentException> { RedisEventBus("Survival 1", InMemoryTransport()) }
    }

    private fun connected(playerId: UUID) = PlayerConnected(
        metadata = de.titansmp.api.event.EventMetadata(eventType = PlayerConnected.TYPE, sourceServerId = "smp-01"),
        playerId = playerId,
        username = "TitanUser",
        serverId = "smp-01",
    )

    private class InMemoryTransport : RedisPubSubTransport {
        private var receiver: ((String) -> Unit)? = null
        private var channel: String? = null
        override fun subscribe(channel: String, receiver: (String) -> Unit) { this.channel = channel; this.receiver = receiver }
        override fun publish(channel: String, message: String): CompletableFuture<Long> {
            if (this.channel == channel) receiver?.invoke(message)
            return CompletableFuture.completedFuture(1L)
        }
        override fun close() { receiver = null }
    }
}
