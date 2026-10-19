package de.titansmp.core.discord.command

import de.titansmp.api.discord.DiscordLinkException
import de.titansmp.api.discord.DiscordLinkService
import de.titansmp.core.discord.role.DiscordRoleNotifier
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.logging.Level
import java.util.logging.Logger

/** Reply text for one Discord slash command. Always ephemeral so codes and errors stay private. */
data class DiscordCommandResult(val message: String)

/**
 * Platform-independent logic behind the Discord `/link` and `/unlink` slash commands.
 *
 * `/link <code>` consumes the code, then grants the verified role. If the role cannot be granted the
 * DB link is kept and the user gets a warning instead of a false success: the link is durable, so the
 * correct recovery is a manual role grant, not a rollback. `/unlink` removes the link and revokes the
 * role best-effort. All failures are translated into understandable replies; link codes and tokens are
 * never logged.
 */
class DiscordLinkCommandHandler(
    private val links: DiscordLinkService,
    private val roles: DiscordRoleNotifier,
    private val logger: Logger = Logger.getLogger(DiscordLinkCommandHandler::class.java.name),
) {
    fun link(discordUserId: Long, code: String): CompletionStage<DiscordCommandResult> =
        links.link(discordUserId, code)
            .thenCompose { grantRole(discordUserId) }
            .exceptionally { failure -> DiscordCommandResult(linkFailureMessage(unwrap(failure))) }

    fun unlink(discordUserId: Long): CompletionStage<DiscordCommandResult> =
        links.unlinkByDiscord(discordUserId)
            .thenCompose { removed ->
                if (removed == null) CompletableFuture.completedFuture(DiscordCommandResult("Your Discord account is not linked to a Minecraft account."))
                else revokeRole(discordUserId)
            }
            .exceptionally { failure ->
                logger.log(Level.WARNING, "An account link could not be removed", unwrap(failure))
                DiscordCommandResult("The account link could not be removed right now. Please try again later.")
            }

    private fun grantRole(discordUserId: Long): CompletionStage<DiscordCommandResult> =
        roles.grant(discordUserId).handle { _, failure ->
            if (failure == null) {
                DiscordCommandResult("Your Discord account is now linked to your Minecraft account.")
            } else {
                logger.log(Level.WARNING, "A Discord account was linked but the verified role could not be granted", failure)
                DiscordCommandResult("Your account was linked, but the verified role could not be assigned. Please contact an administrator.")
            }
        }

    private fun revokeRole(discordUserId: Long): CompletionStage<DiscordCommandResult> =
        roles.revoke(discordUserId).handle { _, failure ->
            if (failure == null) {
                DiscordCommandResult("Your account link has been removed.")
            } else {
                logger.log(Level.WARNING, "An account link was removed but the verified role could not be revoked", failure)
                DiscordCommandResult("Your account link was removed, but the verified role could not be removed. Please contact an administrator.")
            }
        }

    private fun linkFailureMessage(failure: Throwable): String = when ((failure as? DiscordLinkException)?.code) {
        DiscordLinkException.Code.INVALID_CODE,
        DiscordLinkException.Code.EXPIRED_CODE,
        DiscordLinkException.Code.USED_CODE,
        null,
        -> "This link code is invalid or has expired."

        DiscordLinkException.Code.ALREADY_LINKED_PLAYER -> "This Minecraft account is already linked to a Discord account."
        DiscordLinkException.Code.ALREADY_LINKED_DISCORD -> "Your Discord account is already linked to a Minecraft account."
        DiscordLinkException.Code.RATE_LIMITED -> "Too many link attempts. Please wait a moment and try again."
        DiscordLinkException.Code.DATABASE_FAILURE -> "The link could not be created right now. Please try again later."
    }

    private fun unwrap(failure: Throwable): Throwable {
        var current = failure
        while (current is CompletionException && current.cause != null) current = current.cause!!
        return current
    }
}
