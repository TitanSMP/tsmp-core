package de.titansmp.api.display

import de.titansmp.api.event.EventMetadata
import de.titansmp.api.event.EventType
import de.titansmp.api.event.NetworkEvent
import java.util.UUID

/**
 * Published when a player's assembled display meta changed.
 *
 * The event carries the new [PlayerDisplayMeta] so TAB, nametags, chat, and future consumers (Discord,
 * Web API) can react immediately. LuckPerms remains the source of truth: this is a change notification,
 * not a durable prefix store. The origin [EventMetadata.sourceServerId] lets a publisher ignore the
 * echo of its own event.
 */
data class PlayerDisplayMetaChanged(
    override val metadata: EventMetadata,
    val playerUuid: UUID,
    val displayMeta: PlayerDisplayMeta,
) : NetworkEvent {
    companion object { val TYPE = EventType("display.player_meta_changed") }
}
