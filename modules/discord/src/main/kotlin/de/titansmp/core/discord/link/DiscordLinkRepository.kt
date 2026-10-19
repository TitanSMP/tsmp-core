package de.titansmp.core.discord.link

import de.titansmp.api.discord.DiscordAccountLink
import java.time.Instant
import java.util.UUID

/** Outcome of atomically marking a link token as used. */
sealed interface LinkTokenConsumption {
    /** Exactly one caller wins the atomic mark-used update; it receives the token owner. */
    data class Matched(val playerUuid: UUID, val playerName: String) : LinkTokenConsumption

    /** No token with the given hash exists. */
    data object NotFound : LinkTokenConsumption

    /** The token exists but its expiry is in the past. */
    data object Expired : LinkTokenConsumption

    /** The token exists but was already redeemed. */
    data object AlreadyUsed : LinkTokenConsumption
}

/** Outcome of inserting a link; the unique constraints are the last safety net against races. */
enum class LinkInsertOutcome { CREATED, PLAYER_TAKEN, DISCORD_TAKEN }

/** Persistence port for Discord account links and single-use link tokens. */
interface DiscordLinkRepository {
    fun findLinkByPlayer(playerUuid: UUID): DiscordAccountLink?
    fun findLinkByDiscord(discordUserId: Long): DiscordAccountLink?

    /** Replaces any unused token of the player with a fresh one inside one transaction. */
    fun replaceLinkToken(tokenHash: String, playerUuid: UUID, playerName: String, createdAt: Instant, expiresAt: Instant)

    /** Atomically marks the token used and reports the token owner or the reason it cannot be used. */
    fun consumeToken(tokenHash: String, now: Instant): LinkTokenConsumption

    /** Inserts a link, mapping unique-constraint violations to a deterministic outcome. */
    fun insertLink(link: DiscordAccountLink): LinkInsertOutcome

    /** Deletes and returns the player's link, or `null` when none exists. */
    fun deleteLinkByPlayer(playerUuid: UUID): DiscordAccountLink?

    /** Deletes and returns the Discord user's link, or `null` when none exists. */
    fun deleteLinkByDiscord(discordUserId: Long): DiscordAccountLink?
}
