package de.titansmp.core.discord.role

import java.util.concurrent.CompletionStage

/**
 * Platform-independent hook the Discord bot implements with its library to grant or revoke the
 * configured verified role. Keeping this as an interface lets the linking flow be tested without a
 * Discord library and keeps role semantics out of the domain service.
 */
interface DiscordRoleNotifier {
    /** Grants the configured verified role to the Discord user. */
    fun grant(discordUserId: Long): CompletionStage<Unit>

    /** Revokes the configured verified role from the Discord user (best effort). */
    fun revoke(discordUserId: Long): CompletionStage<Unit>
}
