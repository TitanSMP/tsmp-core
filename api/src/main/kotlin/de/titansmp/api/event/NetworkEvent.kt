package de.titansmp.api.event

import java.time.Instant
import java.util.UUID

@JvmInline
value class EventType(val value: String) {
    init {
        require(value.matches(Regex("[a-z][a-z0-9_.-]{0,127}"))) { "Invalid event type" }
    }
}

data class EventMetadata(
    val eventId: UUID = UUID.randomUUID(),
    val eventType: EventType,
    val timestamp: Instant = Instant.now(),
    val sourceServerId: String,
) {
    init { require(sourceServerId.isNotBlank()) { "Source server ID must not be blank" } }
}

/** Platform-independent domain event. Payloads must be immutable values suitable for transport. */
interface NetworkEvent {
    val metadata: EventMetadata
}

/** Public event boundary; the wire format and JSON implementation remain inside core. */
data class EventEnvelope(val event: NetworkEvent) {
    val metadata: EventMetadata get() = event.metadata
}

fun interface EventListener {
    fun onEvent(envelope: EventEnvelope)
}

fun interface EventSubscription : AutoCloseable {
    override fun close()
}

interface EventBus : AutoCloseable {
    fun publish(event: NetworkEvent): java.util.concurrent.CompletionStage<Unit>
    fun subscribe(eventType: EventType, listener: EventListener): EventSubscription
    override fun close()
}
