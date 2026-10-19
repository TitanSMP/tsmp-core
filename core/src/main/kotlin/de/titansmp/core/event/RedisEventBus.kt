package de.titansmp.core.event

import de.titansmp.api.event.EventBus
import de.titansmp.api.event.EventEnvelope
import de.titansmp.api.event.EventListener
import de.titansmp.api.event.EventSubscription
import de.titansmp.api.event.EventType
import de.titansmp.api.event.NetworkEvent
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RedisEventBus(
    networkId: String,
    private val transport: RedisPubSubTransport,
    private val serializer: NetworkEventSerializer = NetworkEventSerializer(),
    private val handlers: ExecutorService = ThreadPoolExecutor(
        2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(4096),
        { task -> Thread(task, "titansmp-event-handler").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    ),
) : EventBus {
    val channel: String
    private val closed = AtomicBoolean(false)
    private val listeners = ConcurrentHashMap<EventType, CopyOnWriteArrayList<EventListener>>()
    private val logger = LoggerFactory.getLogger(RedisEventBus::class.java)

    init {
        require(networkId.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) { "Invalid network namespace ID" }
        channel = "titansmp:$networkId:events:v1"
        try {
            transport.subscribe(channel, ::receive)
        } catch (failure: Exception) {
            handlers.shutdownNow()
            transport.close()
            throw EventBusException("Could not subscribe to Redis event channel", failure)
        }
    }

    override fun publish(event: NetworkEvent): CompletionStage<Unit> {
        check(!closed.get()) { "Event bus is closed" }
        val result = CompletableFuture<Unit>()
        val json = try {
            serializer.serialize(EventEnvelope(event))
        } catch (failure: Exception) {
            return CompletableFuture.failedFuture(failure)
        }
        try {
            transport.publish(channel, json).whenComplete { _, failure ->
                if (failure != null) {
                    logger.error("Redis event publish failed for type {} from server {}", event.metadata.eventType.value, event.metadata.sourceServerId)
                    result.completeExceptionally(EventBusException("Redis event publish failed", failure))
                } else result.complete(Unit)
            }
        } catch (failure: Exception) {
            logger.error("Redis event publish failed for type {} from server {}", event.metadata.eventType.value, event.metadata.sourceServerId)
            result.completeExceptionally(EventBusException("Redis event publish failed", failure))
        }
        return result
    }

    override fun subscribe(eventType: EventType, listener: EventListener): EventSubscription {
        check(!closed.get()) { "Event bus is closed" }
        val group = listeners.computeIfAbsent(eventType) { CopyOnWriteArrayList() }
        group += listener
        return EventSubscription {
            group -= listener
            if (group.isEmpty()) listeners.remove(eventType, group)
        }
    }

    private fun receive(message: String) {
        if (closed.get()) return
        val envelope = try {
            serializer.deserialize(message)
        } catch (failure: Exception) {
            logger.warn("Ignoring invalid or unsupported TitanSMP event message ({})", failure.javaClass.simpleName)
            return
        }
        val targets = listeners[envelope.metadata.eventType]?.toList().orEmpty()
        targets.forEach { listener ->
            try {
                handlers.execute {
                    if (closed.get()) return@execute
                    try {
                        listener.onEvent(envelope)
                    } catch (failure: Throwable) {
                        logger.error("Event listener failed for type {} (event {})", envelope.metadata.eventType.value, envelope.metadata.eventId, failure)
                    }
                }
            } catch (failure: Exception) {
                if (!closed.get()) logger.error("Could not schedule event handler for type {}", envelope.metadata.eventType.value, failure)
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        listeners.clear()
        try {
            transport.close()
        } finally {
            handlers.shutdown()
            var interrupted = false
            try {
                while (!handlers.isTerminated) {
                    try {
                        if (!handlers.awaitTermination(10, TimeUnit.SECONDS)) handlers.shutdownNow()
                    } catch (_: InterruptedException) {
                        interrupted = true
                        handlers.shutdownNow()
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }
}

class EventBusException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
