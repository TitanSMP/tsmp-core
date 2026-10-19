package de.titansmp.core.event

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import de.titansmp.api.event.EventEnvelope
import de.titansmp.api.event.EventMetadata
import de.titansmp.api.event.EventType
import de.titansmp.api.player.event.PlayerConnected
import de.titansmp.api.player.event.PlayerDisconnected
import de.titansmp.api.player.event.PlayerServerChanged
import java.time.Instant
import java.util.UUID

/** Versioned JSON codec for public events. No JSON type or annotation leaks into api. */
class NetworkEventSerializer(
    private val mapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS),
) {
    fun serialize(envelope: EventEnvelope): String {
        val root = mapper.createObjectNode()
        root.put("schemaVersion", SCHEMA_VERSION)
        root.put("eventId", envelope.metadata.eventId.toString())
        root.put("eventType", envelope.metadata.eventType.value)
        root.put("timestamp", envelope.metadata.timestamp.toString())
        root.put("sourceServerId", envelope.metadata.sourceServerId)
        root.set<ObjectNode>("payload", payload(envelope))
        return mapper.writeValueAsString(root)
    }

    fun deserialize(json: String): EventEnvelope {
        val root = mapper.readTree(json)
        val version = root.path("schemaVersion").asInt(-1)
        require(version == SCHEMA_VERSION) { "Unsupported event schema version: $version" }
        val type = EventType(requiredText(root, "eventType"))
        val metadata = EventMetadata(
            eventId = UUID.fromString(requiredText(root, "eventId")),
            eventType = type,
            timestamp = Instant.parse(requiredText(root, "timestamp")),
            sourceServerId = requiredText(root, "sourceServerId"),
        )
        val payload = root.path("payload")
        val event = when (type) {
            PlayerConnected.TYPE -> PlayerConnected(metadata, payload.uuid("playerId"), payload.text("username"), payload.text("serverId"))
            PlayerDisconnected.TYPE -> PlayerDisconnected(metadata, payload.uuid("playerId"), payload.text("serverId"))
            PlayerServerChanged.TYPE -> PlayerServerChanged(metadata, payload.uuid("playerId"), payload.text("previousServerId"), payload.text("newServerId"))
            else -> throw UnknownEventTypeException(type)
        }
        return EventEnvelope(event)
    }

    private fun payload(envelope: EventEnvelope): ObjectNode {
        val node = mapper.createObjectNode()
        when (val event = envelope.event) {
            is PlayerConnected -> {
                node.put("playerId", event.playerId.toString())
                node.put("username", event.username)
                node.put("serverId", event.serverId)
            }
            is PlayerDisconnected -> {
                node.put("playerId", event.playerId.toString())
                node.put("serverId", event.serverId)
            }
            is PlayerServerChanged -> {
                node.put("playerId", event.playerId.toString())
                node.put("previousServerId", event.previousServerId)
                node.put("newServerId", event.newServerId)
            }
            else -> throw UnsupportedEventTypeException(event.metadata.eventType)
        }
        return node
    }

    private fun requiredText(node: JsonNode, field: String): String = node.path(field).textValue()
        ?: throw IllegalArgumentException("Event envelope is missing '$field'")

    private fun JsonNode.text(field: String): String = get(field)?.textValue()
        ?: throw IllegalArgumentException("Event payload is missing '$field'")

    private fun JsonNode.uuid(field: String): UUID = UUID.fromString(text(field))

    companion object { const val SCHEMA_VERSION = 1 }
}

class UnknownEventTypeException(val eventType: EventType) : IllegalArgumentException("Unknown event type '${eventType.value}'")
class UnsupportedEventTypeException(eventType: EventType) : IllegalArgumentException("No serializer registered for event type '${eventType.value}'")
