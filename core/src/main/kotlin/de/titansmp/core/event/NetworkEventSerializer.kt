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
import de.titansmp.api.chat.ChatMessage
import de.titansmp.api.chat.ChatMessageSource
import de.titansmp.api.chat.NetworkChatMessage
import de.titansmp.api.display.PlayerDisplayMeta
import de.titansmp.api.display.PlayerDisplayMetaChanged
import de.titansmp.api.discord.DiscordAccountUnlinked
import de.titansmp.api.economy.EconomyBalanceChanged
import de.titansmp.api.economy.EconomyReason
import de.titansmp.api.economy.Money
import de.titansmp.api.servertransfer.ServerQueueLeaveReason
import de.titansmp.api.servertransfer.ServerQueueLeft
import de.titansmp.api.servertransfer.ServerQueueTransferCompleted
import de.titansmp.api.servertransfer.ServerQueueTransferFailed
import de.titansmp.api.servertransfer.ServerQueueTransferStarted
import de.titansmp.api.servertransfer.ServerQueueUpdated
import de.titansmp.api.stats.PlayerStats
import de.titansmp.api.stats.PlayerStatsUpdated
import de.titansmp.api.friends.*
import de.titansmp.api.clans.*
import de.titansmp.api.teleport.TeleportTransferRequested
import de.titansmp.api.teleport.TeleportRequestCreated
import de.titansmp.api.teleport.TeleportDirection
import de.titansmp.api.teleport.TeleportCooldownCategory
import de.titansmp.api.teleport.TeleportRequestResolved
import de.titansmp.api.teleport.TeleportLocationRequested
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
            FriendRequestCreated.TYPE -> FriendRequestCreated(metadata, payload.uuid("requesterUuid"), payload.uuid("targetUuid"), payload.text("requesterName"))
            FriendRequestAccepted.TYPE -> FriendRequestAccepted(metadata, payload.uuid("playerUuid"), payload.uuid("requesterUuid"))
            FriendRequestRejected.TYPE -> FriendRequestRejected(metadata, payload.uuid("playerUuid"), payload.uuid("requesterUuid"))
            FriendRemoved.TYPE -> FriendRemoved(metadata, payload.uuid("playerUuid"), payload.uuid("friendUuid"))
            FriendBlocked.TYPE -> FriendBlocked(metadata, payload.uuid("blockerUuid"), payload.uuid("blockedUuid"))
            FriendUnblocked.TYPE -> FriendUnblocked(metadata, payload.uuid("blockerUuid"), payload.uuid("blockedUuid"))
            in ClanChanged.TYPES.values -> ClanChanged(
                metadata, ClanChanged.TYPES.entries.first { it.value == type }.key,
                payload.uuid("clanUuid"), payload.uuid("actorUuid"), payload.nullableUuid("targetUuid"),
                payload.nullableText("role")?.let(ClanRole::valueOf), payload.text("clanName"), payload.text("clanTag"),
                payload.path("affectedMembers").map { UUID.fromString(it.asText()) },
            )
            ClanChatMessage.TYPE -> ClanChatMessage(metadata, payload.uuid("clanUuid"), payload.text("clanTag"), ClanColorMode.valueOf(payload.text("colorMode")), payload.text("colorValue"), payload.uuid("senderUuid"), payload.text("senderName"), payload.text("message"))
            TeleportTransferRequested.TYPE -> TeleportTransferRequested(metadata,payload.uuid("operationId"),payload.uuid("playerUuid"),payload.nullableText("targetServerId"),payload.uuid("cooldownOwnerUuid"),TeleportCooldownCategory.valueOf(payload.text("cooldownCategory")),payload.nullableText("cooldownScope"),payload.nullableText("targetServerType"))
            TeleportRequestCreated.TYPE -> TeleportRequestCreated(metadata,payload.uuid("requestId"),payload.uuid("requesterUuid"),payload.uuid("targetUuid"),payload.text("requesterName"),TeleportDirection.valueOf(payload.text("direction")),payload.instant("expiresAt"))
            TeleportRequestResolved.TYPE -> TeleportRequestResolved(metadata,payload.uuid("requestId"),payload.uuid("requesterUuid"),payload.uuid("targetUuid"),payload.path("accepted").asBoolean())
            TeleportLocationRequested.TYPE -> TeleportLocationRequested(metadata,payload.uuid("operationId"),payload.uuid("requesterUuid"),payload.uuid("movingPlayerUuid"))
            PlayerDisconnected.TYPE -> PlayerDisconnected(metadata, payload.uuid("playerId"), payload.text("serverId"))
            PlayerServerChanged.TYPE -> PlayerServerChanged(metadata, payload.uuid("playerId"), payload.text("previousServerId"), payload.text("newServerId"))
            PlayerBanned.TYPE -> PlayerBanned(metadata, payload.uuid("targetId"), payload.text("targetName"), payload.uuid("actionId"), payload.nullableUuid("actorId"), payload.text("actorName"), payload.text("reason"), payload.instant("createdAt"), payload.nullableInstant("expiresAt"), payload.text("serverId"))
            PlayerUnbanned.TYPE -> PlayerUnbanned(metadata, payload.uuid("targetId"), payload.text("targetName"), payload.uuid("actionId"), payload.nullableUuid("actorId"), payload.text("actorName"), payload.nullableText("reason"), payload.instant("createdAt"), payload.text("serverId"))
            PlayerMuted.TYPE -> PlayerMuted(metadata, payload.uuid("targetId"), payload.text("targetName"), payload.uuid("actionId"), payload.nullableUuid("actorId"), payload.text("actorName"), payload.text("reason"), payload.instant("createdAt"), payload.nullableInstant("expiresAt"), payload.text("serverId"))
            PlayerUnmuted.TYPE -> PlayerUnmuted(metadata, payload.uuid("targetId"), payload.text("targetName"), payload.uuid("actionId"), payload.nullableUuid("actorId"), payload.text("actorName"), payload.nullableText("reason"), payload.instant("createdAt"), payload.text("serverId"))
            PlayerKicked.TYPE -> PlayerKicked(metadata, payload.uuid("targetId"), payload.text("targetName"), payload.uuid("actionId"), payload.nullableUuid("actorId"), payload.text("actorName"), payload.text("reason"), payload.instant("createdAt"), payload.text("serverId"))
            NetworkChatMessage.TYPE -> NetworkChatMessage(metadata, ChatMessage(
                payload.uuid("messageId"), payload.nullableUuid("senderUuid"), payload.text("senderName"), payload.text("message"),
                payload.text("serverId"), payload.instant("timestamp"), ChatMessageSource.valueOf(payload.text("source")),
                payload.path("prefix").textValue() ?: "",
            ))
            PlayerDisplayMetaChanged.TYPE -> {
                val playerUuid = payload.uuid("playerUuid")
                PlayerDisplayMetaChanged(
                    metadata,
                    playerUuid,
                    PlayerDisplayMeta(
                        playerUuid,
                        payload.path("prefix").textValue() ?: "",
                        payload.nullableText("suffix"),
                        payload.path("weight").intValue(),
                    ),
                )
            }
            PlayerStateUpdated.TYPE -> PlayerStateUpdated(metadata, payload.uuid("playerUuid"), payload.path("revision").longValue())
            PlayerTransferPrepare.TYPE -> PlayerTransferPrepare(metadata, payload.uuid("transferId"), payload.uuid("playerUuid"), payload.text("sourceServerId"), payload.text("targetServerId"), payload.instant("timestamp"))
            PlayerTransferReady.TYPE -> PlayerTransferReady(metadata, payload.uuid("transferId"), payload.uuid("playerUuid"), payload.text("sourceServerId"), payload.text("targetServerId"), payload.path("stateRevision").longValue(), payload.instant("timestamp"))
            PlayerTransferTargetPrepared.TYPE -> PlayerTransferTargetPrepared(metadata, payload.uuid("transferId"), payload.uuid("playerUuid"), payload.text("sourceServerId"), payload.text("targetServerId"), payload.instant("timestamp"))
            PlayerTransferFailed.TYPE -> PlayerTransferFailed(metadata, payload.uuid("transferId"), payload.uuid("playerUuid"), payload.text("sourceServerId"), payload.text("targetServerId"), payload.text("reason"), payload.instant("timestamp"))
            PlayerTransferCompleted.TYPE -> PlayerTransferCompleted(metadata, payload.uuid("transferId"), payload.uuid("playerUuid"), payload.text("sourceServerId"), payload.text("targetServerId"), payload.path("stateRevision").longValue(), payload.instant("timestamp"))
            DiscordAccountUnlinked.TYPE -> DiscordAccountUnlinked(metadata, payload.uuid("playerUuid"), payload.text("playerName"), payload.path("discordUserId").longValue())
            EconomyBalanceChanged.TYPE -> EconomyBalanceChanged(
                metadata,
                payload.uuid("transactionId"),
                payload.uuid("playerUuid"),
                payload.money("amount"),
                payload.money("oldBalance"),
                payload.money("newBalance"),
                EconomyReason.valueOf(payload.text("reason")),
                payload.nullableUuid("counterpartyUuid"),
                payload.nullableText("counterpartyName"),
                payload.nullableUuid("actorUuid"),
                payload.text("sourceServerId"),
            )
            ServerQueueUpdated.TYPE -> ServerQueueUpdated(
                metadata,
                payload.uuid("playerUuid"),
                payload.text("serverType"),
                payload.path("position").intValue(),
                payload.path("size").intValue(),
                payload.path("priority").intValue(),
            )
            ServerQueueLeft.TYPE -> ServerQueueLeft(
                metadata,
                payload.uuid("playerUuid"),
                payload.text("serverType"),
                ServerQueueLeaveReason.valueOf(payload.text("reason")),
            )
            ServerQueueTransferStarted.TYPE -> ServerQueueTransferStarted(
                metadata,
                payload.uuid("playerUuid"),
                payload.text("serverType"),
                payload.text("targetServerId"),
                payload.uuid("reservationId"),
            )
            ServerQueueTransferCompleted.TYPE -> ServerQueueTransferCompleted(
                metadata,
                payload.uuid("playerUuid"),
                payload.text("serverType"),
                payload.text("targetServerId"),
                payload.uuid("reservationId"),
            )
            ServerQueueTransferFailed.TYPE -> ServerQueueTransferFailed(
                metadata,
                payload.uuid("playerUuid"),
                payload.text("serverType"),
                payload.text("targetServerId"),
                payload.uuid("reservationId"),
                payload.text("reason"),
            )
            PlayerStatsUpdated.TYPE -> PlayerStatsUpdated(
                metadata,
                payload.uuid("playerUuid"),
                PlayerStats(
                    playerUuid = payload.uuid("playerUuid"),
                    playtimeSeconds = payload.path("playtime").longValue(),
                    joins = payload.path("joins").longValue(),
                    deaths = payload.path("deaths").longValue(),
                    blocksMined = payload.path("blocksMined").longValue(),
                    blocksWalked = payload.path("blocksWalked").longValue(),
                    blocksElytra = payload.path("blocksElytra").longValue(),
                    damageTaken = payload.path("damageTaken").longValue(),
                    damageDealt = payload.path("damageDealt").longValue(),
                    mobsKilled = payload.path("mobsKilled").longValue(),
                    playersKilled = payload.path("playersKilled").longValue(),
                    updatedAt = payload.instant("updatedAt"),
                ),
            )
            else -> throw UnknownEventTypeException(type)
        }
        return EventEnvelope(event)
    }

    private fun payload(envelope: EventEnvelope): ObjectNode {
        val node = mapper.createObjectNode()
        when (val event = envelope.event) {
            is FriendRequestCreated -> { node.put("requesterUuid", event.requesterUuid.toString()); node.put("targetUuid", event.targetUuid.toString()); node.put("requesterName", event.requesterName) }
            is FriendRequestAccepted -> { node.put("playerUuid", event.playerUuid.toString()); node.put("requesterUuid", event.requesterUuid.toString()) }
            is FriendRequestRejected -> { node.put("playerUuid", event.playerUuid.toString()); node.put("requesterUuid", event.requesterUuid.toString()) }
            is FriendRemoved -> { node.put("playerUuid", event.playerUuid.toString()); node.put("friendUuid", event.friendUuid.toString()) }
            is FriendBlocked -> { node.put("blockerUuid", event.blockerUuid.toString()); node.put("blockedUuid", event.blockedUuid.toString()) }
            is FriendUnblocked -> { node.put("blockerUuid", event.blockerUuid.toString()); node.put("blockedUuid", event.blockedUuid.toString()) }
            is ClanChanged -> {
                node.put("action", event.action.name); node.put("clanUuid", event.clanUuid.toString()); node.put("actorUuid", event.actorUuid.toString())
                if (event.targetUuid == null) node.putNull("targetUuid") else node.put("targetUuid", event.targetUuid.toString())
                event.role?.let { node.put("role",it.name) } ?: node.putNull("role")
                node.put("clanName", event.clanName); node.put("clanTag", event.clanTag)
                node.putArray("affectedMembers").also { array -> event.affectedMembers.forEach { array.add(it.toString()) } }
            }
            is ClanChatMessage -> {
                node.put("clanUuid",event.clanUuid.toString()); node.put("clanTag",event.clanTag); node.put("colorMode",event.colorMode.name)
                node.put("colorValue",event.colorValue); node.put("senderUuid",event.senderUuid.toString()); node.put("senderName",event.senderName); node.put("message",event.message)
            }
            is TeleportTransferRequested -> { node.put("operationId",event.operationId.toString());node.put("playerUuid",event.playerUuid.toString());if(event.targetServerId==null)node.putNull("targetServerId") else node.put("targetServerId",event.targetServerId);node.put("cooldownOwnerUuid",event.cooldownOwnerUuid.toString());node.put("cooldownCategory",event.cooldownCategory.name);if(event.cooldownScope==null)node.putNull("cooldownScope") else node.put("cooldownScope",event.cooldownScope);if(event.targetServerType==null)node.putNull("targetServerType") else node.put("targetServerType",event.targetServerType) }
            is TeleportRequestCreated -> { node.put("requestId",event.requestId.toString());node.put("requesterUuid",event.requesterUuid.toString());node.put("targetUuid",event.targetUuid.toString());node.put("requesterName",event.requesterName);node.put("direction",event.direction.name);node.put("expiresAt",event.expiresAt.toString()) }
            is TeleportRequestResolved -> { node.put("requestId",event.requestId.toString());node.put("requesterUuid",event.requesterUuid.toString());node.put("targetUuid",event.targetUuid.toString());node.put("accepted",event.accepted) }
            is TeleportLocationRequested -> { node.put("operationId",event.operationId.toString());node.put("requesterUuid",event.requesterUuid.toString());node.put("movingPlayerUuid",event.movingPlayerUuid.toString()) }
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
            is NetworkChatMessage -> {
                val message = event.message
                node.put("messageId", message.messageId.toString())
                if (message.senderUuid == null) node.putNull("senderUuid") else node.put("senderUuid", message.senderUuid.toString())
                node.put("senderName", message.senderName); node.put("message", message.message); node.put("serverId", message.serverId)
                node.put("timestamp", message.timestamp.toString()); node.put("source", message.source.name)
                node.put("prefix", message.prefix)
            }
            is PlayerDisplayMetaChanged -> {
                val meta = event.displayMeta
                node.put("playerUuid", event.playerUuid.toString())
                node.put("prefix", meta.prefix)
                if (meta.suffix == null) node.putNull("suffix") else node.put("suffix", meta.suffix)
                node.put("weight", meta.weight)
            }
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
            is DiscordAccountUnlinked -> {
                node.put("playerUuid", event.playerUuid.toString())
                node.put("playerName", event.playerName)
                node.put("discordUserId", event.discordUserId)
            }
            is EconomyBalanceChanged -> {
                node.put("transactionId", event.transactionId.toString())
                node.put("playerUuid", event.playerUuid.toString())
                node.put("amount", event.amount.minorUnits)
                node.put("oldBalance", event.oldBalance.minorUnits)
                node.put("newBalance", event.newBalance.minorUnits)
                node.put("reason", event.reason.name)
                if (event.counterpartyUuid == null) node.putNull("counterpartyUuid") else node.put("counterpartyUuid", event.counterpartyUuid.toString())
                if (event.counterpartyName == null) node.putNull("counterpartyName") else node.put("counterpartyName", event.counterpartyName)
                if (event.actorUuid == null) node.putNull("actorUuid") else node.put("actorUuid", event.actorUuid.toString())
                node.put("sourceServerId", event.sourceServerId)
            }
            is ServerQueueUpdated -> {
                node.put("playerUuid", event.playerUuid.toString())
                node.put("serverType", event.serverType)
                node.put("position", event.position)
                node.put("size", event.size)
                node.put("priority", event.priority)
            }
            is ServerQueueLeft -> {
                node.put("playerUuid", event.playerUuid.toString())
                node.put("serverType", event.serverType)
                node.put("reason", event.reason.name)
            }
            is ServerQueueTransferStarted -> {
                node.put("playerUuid", event.playerUuid.toString())
                node.put("serverType", event.serverType)
                node.put("targetServerId", event.targetServerId)
                node.put("reservationId", event.reservationId.toString())
            }
            is ServerQueueTransferCompleted -> {
                node.put("playerUuid", event.playerUuid.toString())
                node.put("serverType", event.serverType)
                node.put("targetServerId", event.targetServerId)
                node.put("reservationId", event.reservationId.toString())
            }
            is ServerQueueTransferFailed -> {
                node.put("playerUuid", event.playerUuid.toString())
                node.put("serverType", event.serverType)
                node.put("targetServerId", event.targetServerId)
                node.put("reservationId", event.reservationId.toString())
                node.put("reason", event.reason)
            }
            is PlayerStatsUpdated -> {
                val stats = event.stats
                node.put("playerUuid", stats.playerUuid.toString())
                node.put("playtime", stats.playtimeSeconds)
                node.put("joins", stats.joins)
                node.put("deaths", stats.deaths)
                node.put("blocksMined", stats.blocksMined)
                node.put("blocksWalked", stats.blocksWalked)
                node.put("blocksElytra", stats.blocksElytra)
                node.put("damageTaken", stats.damageTaken)
                node.put("damageDealt", stats.damageDealt)
                node.put("mobsKilled", stats.mobsKilled)
                node.put("playersKilled", stats.playersKilled)
                node.put("updatedAt", stats.updatedAt.toString())
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
    private fun JsonNode.money(field: String): Money {
        val node = get(field) ?: throw IllegalArgumentException("Event payload is missing '$field'")
        val minorUnits = when {
            node.isIntegralNumber -> node.longValue()
            node.isTextual -> node.textValue().toLongOrNull()
                ?: throw IllegalArgumentException("Event payload '$field' is not a minor-unit amount")
            else -> throw IllegalArgumentException("Event payload '$field' is not a minor-unit amount")
        }
        return Money.ofMinor(minorUnits)
    }

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
