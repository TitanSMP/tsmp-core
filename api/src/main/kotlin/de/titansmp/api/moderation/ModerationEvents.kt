package de.titansmp.api.moderation

import de.titansmp.api.event.EventMetadata
import de.titansmp.api.event.EventType
import de.titansmp.api.event.NetworkEvent
import java.time.Instant
import java.util.UUID

data class PlayerBanned(override val metadata: EventMetadata, val targetId: UUID, val targetName: String, val actionId: UUID, val actorId: UUID?, val actorName: String, val reason: String, val createdAt: Instant, val expiresAt: Instant?, val serverId: String) : NetworkEvent {
    companion object { val TYPE = EventType("moderation.player_banned") }
}
data class PlayerUnbanned(override val metadata: EventMetadata, val targetId: UUID, val targetName: String, val actionId: UUID, val actorId: UUID?, val actorName: String, val reason: String?, val createdAt: Instant, val serverId: String) : NetworkEvent {
    companion object { val TYPE = EventType("moderation.player_unbanned") }
}
data class PlayerMuted(override val metadata: EventMetadata, val targetId: UUID, val targetName: String, val actionId: UUID, val actorId: UUID?, val actorName: String, val reason: String, val createdAt: Instant, val expiresAt: Instant?, val serverId: String) : NetworkEvent {
    companion object { val TYPE = EventType("moderation.player_muted") }
}
data class PlayerUnmuted(override val metadata: EventMetadata, val targetId: UUID, val targetName: String, val actionId: UUID, val actorId: UUID?, val actorName: String, val reason: String?, val createdAt: Instant, val serverId: String) : NetworkEvent {
    companion object { val TYPE = EventType("moderation.player_unmuted") }
}
data class PlayerKicked(override val metadata: EventMetadata, val targetId: UUID, val targetName: String, val actionId: UUID, val actorId: UUID?, val actorName: String, val reason: String, val createdAt: Instant, val serverId: String) : NetworkEvent {
    companion object { val TYPE = EventType("moderation.player_kicked") }
}
