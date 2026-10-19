package de.titansmp.api.discord

import de.titansmp.api.event.EventMetadata
import de.titansmp.api.event.EventType
import de.titansmp.api.event.NetworkEvent
import java.util.UUID

/**
 * Published whenever a Minecraft account is unlinked from a Discord account.
 *
 * The Discord bot subscribes to it and removes the configured verified role best-effort. The origin
 * [EventMetadata.sourceServerId] lets the publisher ignore the echo of its own event. The DB state is
 * already durable when this event is published, so a missing bot never corrupts the link state; the
 * role is simply not removed until an administrator does it manually.
 */
data class DiscordAccountUnlinked(
    override val metadata: EventMetadata,
    val playerUuid: UUID,
    val playerName: String,
    val discordUserId: Long,
) : NetworkEvent {
    companion object { val TYPE = EventType("discord.account_unlinked") }
}
