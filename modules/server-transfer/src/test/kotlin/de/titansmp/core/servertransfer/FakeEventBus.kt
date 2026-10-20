package de.titansmp.core.servertransfer

import de.titansmp.api.event.EventBus
import de.titansmp.api.event.EventEnvelope
import de.titansmp.api.event.EventListener
import de.titansmp.api.event.EventSubscription
import de.titansmp.api.event.EventType
import de.titansmp.api.event.NetworkEvent
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList

/** Minimal in-memory event bus used to assert published queue events without Redis. */
class FakeEventBus : EventBus {
    private val published = CopyOnWriteArrayList<NetworkEvent>()
    private val listeners = mutableMapOf<EventType, MutableList<EventListener>>()

    val events: List<NetworkEvent> get() = published.toList()

    override fun publish(event: NetworkEvent): CompletionStage<Unit> {
        published += event
        listeners[event.metadata.eventType]?.toList()?.forEach { it.onEvent(EventEnvelope(event)) }
        return CompletableFuture.completedFuture(Unit)
    }

    override fun subscribe(eventType: EventType, listener: EventListener): EventSubscription {
        val group = listeners.getOrPut(eventType) { CopyOnWriteArrayList() }
        group += listener
        return EventSubscription { group -= listener }
    }

    override fun close() {
        listeners.clear()
    }
}
