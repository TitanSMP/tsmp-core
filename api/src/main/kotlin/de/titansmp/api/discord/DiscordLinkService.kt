package de.titansmp.api.discord

import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletionStage

/**
 * A persisted, bidirectional link between exactly one Minecraft account and exactly one Discord
 * account.
 *
 * The Minecraft [playerUuid] is the stable identity; [playerName] is only the most recently known
 * display name and must never be used as a foreign key. Both directions are unique: a player has at
 * most one link and a Discord user has at most one link.
 */
data class DiscordAccountLink(
    val playerUuid: UUID,
    val playerName: String,
    val discordUserId: Long,
    val linkedAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(playerName.isNotBlank()) { "Linked player name must not be blank" }
        require(discordUserId > 0) { "Discord user ID must be positive" }
    }
}

/**
 * A freshly generated, single-use link code.
 *
 * [code] is the human-readable plaintext code and is returned to the caller exactly once. Only a hash
 * of the normalized code is persisted, so an already created token can never be recovered from the
 * database.
 */
data class DiscordLinkToken(val code: String, val expiresAt: Instant)

/**
 * Platform-independent account linking contract.
 *
 * The implementation owns the MariaDB persistence, the single-use token handling, the attempt rate
 * limit, and the publication of [DiscordAccountUnlinked]. Paper only uses it to create and remove
 * tokens; the Discord bot uses it to consume tokens. No Discord library type is referenced here so
 * Paper can depend on the feature without loading a Discord library.
 */
interface DiscordLinkService {
    /**
     * Creates a new single-use link code for an online player.
     *
     * Fails with [DiscordLinkException.Code.ALREADY_LINKED_PLAYER] when the player already has a link.
     */
    fun createLinkToken(playerUuid: UUID, playerName: String): CompletionStage<DiscordLinkToken>

    /**
     * Consumes a link code and creates the link for the Discord user.
     *
     * The token is marked used atomically, so a code can be redeemed at most once even under
     * concurrent attempts. Exactly one of several concurrent redemptions succeeds; the others receive
     * an understandable failure.
     */
    fun link(discordUserId: Long, code: String): CompletionStage<DiscordAccountLink>

    /** Removes the player's link, publishing [DiscordAccountUnlinked]. Returns the removed link or `null`. */
    fun unlinkByPlayer(playerUuid: UUID): CompletionStage<DiscordAccountLink?>

    /** Removes the Discord user's link, publishing [DiscordAccountUnlinked]. Returns the removed link or `null`. */
    fun unlinkByDiscord(discordUserId: Long): CompletionStage<DiscordAccountLink?>

    /** Returns the link for a Minecraft player, or `null`. */
    fun findByPlayer(playerUuid: UUID): CompletionStage<DiscordAccountLink?>

    /** Returns the link for a Discord user, or `null`. */
    fun findByDiscord(discordUserId: Long): CompletionStage<DiscordAccountLink?>
}

/** Understandable linking failures. Codes are stable so adapters can map them to user-facing text. */
class DiscordLinkException(val code: Code, message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    enum class Code {
        INVALID_CODE,
        EXPIRED_CODE,
        USED_CODE,
        ALREADY_LINKED_PLAYER,
        ALREADY_LINKED_DISCORD,
        RATE_LIMITED,
        DATABASE_FAILURE,
    }
}
