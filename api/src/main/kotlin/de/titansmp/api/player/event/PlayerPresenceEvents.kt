package de.titansmp.api.player.event

import de.titansmp.api.event.EventMetadata
import de.titansmp.api.event.EventType
import de.titansmp.api.event.NetworkEvent
import java.util.UUID

data class PlayerConnected(
    override val metadata: EventMetadata,
    val playerId: UUID,
    val username: String,
    val serverId: String,
) : NetworkEvent {
    companion object { val TYPE = EventType("player.connected") }
}

data class PlayerDisconnected(
    override val metadata: EventMetadata,
    val playerId: UUID,
    val serverId: String,
) : NetworkEvent {
    companion object { val TYPE = EventType("player.disconnected") }
}

data class PlayerServerChanged(
    override val metadata: EventMetadata,
    val playerId: UUID,
    val previousServerId: String,
    val newServerId: String,
) : NetworkEvent {
    companion object { val TYPE = EventType("player.server_changed") }
}
