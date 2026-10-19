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
import de.titansmp.api.databridge.PlayerStateUpdated
import de.titansmp.api.databridge.PlayerTransferPrepare
import de.titansmp.api.databridge.PlayerTransferReady
import de.titansmp.api.databridge.PlayerTransferFailed
import de.titansmp.api.databridge.PlayerTransferCompleted
import de.titansmp.api.databridge.PlayerTransferTargetPrepared
import de.titansmp.api.player.event.PlayerConnected
import de.titansmp.api.player.event.PlayerDisconnected
import de.titansmp.api.player.event.PlayerServerChanged
import de.titansmp.api.moderation.PlayerBanned
import de.titansmp.api.moderation.PlayerUnbanned
import de.titansmp.api.moderation.PlayerMuted
import de.titansmp.api.moderation.PlayerUnmuted
import de.titansmp.api.moderation.PlayerKicked
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
            PlayerBanned.TYPE -> PlayerBanned(metadata, payload.uuid("targetId"), payload.text("targetName"), payload.uuid("actionId"), payload.nullableUuid("actorId"), payload.text("actorName"), payload.text("reason"), payload.instant("createdAt"), payload.nullableInstant("expiresAt"), payload.text("serverId"))
            PlayerUnbanned.TYPE -> PlayerUnbanned(metadata, payload.uuid("targetId"), payload.text("targetName"), payload.uuid("actionId"), payload.nullableUuid("actorId"), payload.text("actorName"), payload.nullableText("reason"), payload.instant("createdAt"), payload.text("serverId"))
            PlayerMuted.TYPE -> PlayerMuted(metadata, payload.uuid("targetId"), payload.text("targetName"), payload.uuid("actionId"), payload.nullableUuid("actorId"), payload.text("actorName"), payload.text("reason"), payload.instant("createdAt"), payload.nullableInstant("expiresAt"), payload.text("serverId"))
            PlayerUnmuted.TYPE -> PlayerUnmuted(metadata, payload.uuid("targetId"), payload.text("targetName"), payload.uuid("actionId"), payload.nullableUuid("actorId"), payload.text("actorName"), payload.nullableText("reason"), payload.instant("createdAt"), payload.text("serverId"))
            PlayerKicked.TYPE -> PlayerKicked(metadata, payload.uuid("targetId"), payload.text("targetName"), payload.uuid("actionId"), payload.nullableUuid("actorId"), payload.text("actorName"), payload.text("reason"), payload.instant("createdAt"), payload.text("serverId"))
            PlayerStateUpdated.TYPE -> PlayerStateUpdated(metadata, payload.uuid("playerUuid"), payload.path("revision").longValue())
            PlayerTransferPrepare.TYPE -> PlayerTransferPrepare(metadata, payload.uuid("transferId"), payload.uuid("playerUuid"), payload.text("sourceServerId"), payload.text("targetServerId"), payload.instant("timestamp"))
            PlayerTransferReady.TYPE -> PlayerTransferReady(metadata, payload.uuid("transferId"), payload.uuid("playerUuid"), payload.text("sourceServerId"), payload.text("targetServerId"), payload.path("stateRevision").longValue(), payload.instant("timestamp"))
            PlayerTransferTargetPrepared.TYPE -> PlayerTransferTargetPrepared(metadata, payload.uuid("transferId"), payload.uuid("playerUuid"), payload.text("sourceServerId"), payload.text("targetServerId"), payload.instant("timestamp"))
            PlayerTransferFailed.TYPE -> PlayerTransferFailed(metadata, payload.uuid("transferId"), payload.uuid("playerUuid"), payload.text("sourceServerId"), payload.text("targetServerId"), payload.text("reason"), payload.instant("timestamp"))
            PlayerTransferCompleted.TYPE -> PlayerTransferCompleted(metadata, payload.uuid("transferId"), payload.uuid("playerUuid"), payload.text("sourceServerId"), payload.text("targetServerId"), payload.path("stateRevision").longValue(), payload.instant("timestamp"))
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
            is PlayerBanned -> node.putModeration(event.targetId, event.targetName, event.actionId, event.actorId, event.actorName, event.reason, event.createdAt, event.expiresAt, event.serverId)
            is PlayerUnbanned -> node.putModeration(event.targetId, event.targetName, event.actionId, event.actorId, event.actorName, event.reason, event.createdAt, null, event.serverId)
            is PlayerMuted -> node.putModeration(event.targetId, event.targetName, event.actionId, event.actorId, event.actorName, event.reason, event.createdAt, event.expiresAt, event.serverId)
            is PlayerUnmuted -> node.putModeration(event.targetId, event.targetName, event.actionId, event.actorId, event.actorName, event.reason, event.createdAt, null, event.serverId)
            is PlayerKicked -> node.putModeration(event.targetId, event.targetName, event.actionId, event.actorId, event.actorName, event.reason, event.createdAt, null, event.serverId)
            is PlayerStateUpdated -> {
                node.put("playerUuid", event.playerUuid.toString())
                node.put("revision", event.revision)
            }
            is PlayerTransferPrepare -> {
                node.put("transferId", event.transferId.toString()); node.put("playerUuid", event.playerUuid.toString())
                node.put("sourceServerId", event.sourceServerId); node.put("targetServerId", event.targetServerId); node.put("timestamp", event.timestamp.toString())
            }
            is PlayerTransferReady -> {
                node.put("transferId", event.transferId.toString()); node.put("playerUuid", event.playerUuid.toString())
                node.put("sourceServerId", event.sourceServerId); node.put("targetServerId", event.targetServerId)
                node.put("stateRevision", event.stateRevision); node.put("timestamp", event.timestamp.toString())
            }
            is PlayerTransferTargetPrepared -> {
                node.put("transferId", event.transferId.toString()); node.put("playerUuid", event.playerUuid.toString())
                node.put("sourceServerId", event.sourceServerId); node.put("targetServerId", event.targetServerId); node.put("timestamp", event.timestamp.toString())
            }
            is PlayerTransferFailed -> {
                node.put("transferId", event.transferId.toString()); node.put("playerUuid", event.playerUuid.toString())
                node.put("sourceServerId", event.sourceServerId); node.put("targetServerId", event.targetServerId)
                node.put("reason", event.reason); node.put("timestamp", event.timestamp.toString())
            }
            is PlayerTransferCompleted -> {
                node.put("transferId", event.transferId.toString()); node.put("playerUuid", event.playerUuid.toString())
                node.put("sourceServerId", event.sourceServerId); node.put("targetServerId", event.targetServerId)
                node.put("stateRevision", event.stateRevision); node.put("timestamp", event.timestamp.toString())
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
    private fun JsonNode.instant(field: String): Instant = Instant.parse(text(field))
    private fun JsonNode.nullableUuid(field: String): UUID? = get(field)?.takeUnless(JsonNode::isNull)?.textValue()?.let(UUID::fromString)
    private fun JsonNode.nullableInstant(field: String): Instant? = get(field)?.takeUnless(JsonNode::isNull)?.textValue()?.let(Instant::parse)
    private fun JsonNode.nullableText(field: String): String? = get(field)?.takeUnless(JsonNode::isNull)?.textValue()

    private fun ObjectNode.putModeration(targetId: UUID, targetName: String, actionId: UUID, actorId: UUID?, actorName: String, reason: String?, createdAt: Instant, expiresAt: Instant?, serverId: String) {
        put("targetId", targetId.toString()); put("targetName", targetName); put("actionId", actionId.toString())
        if (actorId == null) putNull("actorId") else put("actorId", actorId.toString())
        put("actorName", actorName); if (reason == null) putNull("reason") else put("reason", reason)
        put("createdAt", createdAt.toString()); if (expiresAt == null) putNull("expiresAt") else put("expiresAt", expiresAt.toString())
        put("serverId", serverId)
    }

    companion object { const val SCHEMA_VERSION = 1 }
}

class UnknownEventTypeException(val eventType: EventType) : IllegalArgumentException("Unknown event type '${eventType.value}'")
class UnsupportedEventTypeException(eventType: EventType) : IllegalArgumentException("No serializer registered for event type '${eventType.value}'")
