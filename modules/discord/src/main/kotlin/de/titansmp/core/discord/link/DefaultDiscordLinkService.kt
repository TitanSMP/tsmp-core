package de.titansmp.core.discord.link

import de.titansmp.api.discord.DiscordAccountLink
import de.titansmp.api.discord.DiscordAccountUnlinked
import de.titansmp.api.discord.DiscordLinkException
import de.titansmp.api.discord.DiscordLinkService
import de.titansmp.api.discord.DiscordLinkToken
import de.titansmp.api.event.EventBus
import de.titansmp.api.event.EventMetadata
import de.titansmp.core.config.DiscordLinkingConfig
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Shared Discord account-linking service.
 *
 * Both the Paper commands and the Discord bot use this single implementation, so link rules live in one
 * place. It owns:
 *
 * - high-entropy, short-lived, single-use link codes (only their hash is stored),
 * - a per-Discord-user attempt limiter against brute force,
 * - an atomic, race-safe link creation that maps unique-constraint violations to understandable codes,
 * - the [DiscordAccountUnlinked] event other runtimes use to remove the verified role best-effort.
 *
 * JDBC work runs on the supplied [executor] so callers never block on the database.
 */
class DefaultDiscordLinkService(
    private val repository: DiscordLinkRepository,
    private val eventBus: EventBus?,
    private val serverId: String,
    private val linking: DiscordLinkingConfig,
    private val executor: Executor,
    private val clock: Clock = Clock.systemUTC(),
    private val limiter: DiscordLinkAttemptLimiter = DiscordLinkAttemptLimiter(linking.maxAttemptsPerWindow, linking.attemptWindowSeconds, clock),
    private val logger: Logger = Logger.getLogger(DefaultDiscordLinkService::class.java.name),
) : DiscordLinkService {
    override fun createLinkToken(playerUuid: UUID, playerName: String): CompletionStage<DiscordLinkToken> = async {
        if (repository.findLinkByPlayer(playerUuid) != null) {
            throw DiscordLinkException(DiscordLinkException.Code.ALREADY_LINKED_PLAYER, "This Minecraft account is already linked to a Discord account.")
        }
        val code = DiscordLinkCodes.generate()
        val now = clock.instant()
        val expiresAt = now.plusSeconds(linking.tokenExpirationMinutes * 60)
        repository.replaceLinkToken(DiscordLinkCodes.hash(code), playerUuid, playerName, now, expiresAt)
        DiscordLinkToken(DiscordLinkCodes.display(code), expiresAt)
    }

    override fun link(discordUserId: Long, code: String): CompletionStage<DiscordAccountLink> = async {
        if (!limiter.tryAcquire(discordUserId)) {
            throw DiscordLinkException(DiscordLinkException.Code.RATE_LIMITED, "Too many link attempts. Please wait a moment and try again.")
        }
        val now = clock.instant()
        when (val consumption = repository.consumeToken(DiscordLinkCodes.hash(code), now)) {
            is LinkTokenConsumption.NotFound -> throw DiscordLinkException(DiscordLinkException.Code.INVALID_CODE, "This link code is invalid or has expired.")
            is LinkTokenConsumption.Expired -> throw DiscordLinkException(DiscordLinkException.Code.EXPIRED_CODE, "This link code has expired.")
            is LinkTokenConsumption.AlreadyUsed -> throw DiscordLinkException(DiscordLinkException.Code.USED_CODE, "This link code has already been used.")
            is LinkTokenConsumption.Matched -> {
                val playerUuid = consumption.playerUuid
                if (repository.findLinkByPlayer(playerUuid) != null) {
                    throw DiscordLinkException(DiscordLinkException.Code.ALREADY_LINKED_PLAYER, "This Minecraft account is already linked to a Discord account.")
                }
                if (repository.findLinkByDiscord(discordUserId) != null) {
                    throw DiscordLinkException(DiscordLinkException.Code.ALREADY_LINKED_DISCORD, "Your Discord account is already linked to a Minecraft account.")
                }
                val link = DiscordAccountLink(playerUuid, consumption.playerName, discordUserId, now, now)
                when (repository.insertLink(link)) {
                    LinkInsertOutcome.CREATED -> link
                    LinkInsertOutcome.PLAYER_TAKEN -> throw DiscordLinkException(DiscordLinkException.Code.ALREADY_LINKED_PLAYER, "This Minecraft account is already linked to a Discord account.")
                    LinkInsertOutcome.DISCORD_TAKEN -> throw DiscordLinkException(DiscordLinkException.Code.ALREADY_LINKED_DISCORD, "Your Discord account is already linked to a Minecraft account.")
                }
            }
        }
    }

    override fun unlinkByPlayer(playerUuid: UUID): CompletionStage<DiscordAccountLink?> = async {
        repository.deleteLinkByPlayer(playerUuid)?.also(::publishUnlinked)
    }

    override fun unlinkByDiscord(discordUserId: Long): CompletionStage<DiscordAccountLink?> = async {
        repository.deleteLinkByDiscord(discordUserId)?.also(::publishUnlinked)
    }

    override fun findByPlayer(playerUuid: UUID): CompletionStage<DiscordAccountLink?> = async { repository.findLinkByPlayer(playerUuid) }

    override fun findByDiscord(discordUserId: Long): CompletionStage<DiscordAccountLink?> = async { repository.findLinkByDiscord(discordUserId) }

    private fun publishUnlinked(link: DiscordAccountLink) {
        val bus = eventBus ?: return
        val event = DiscordAccountUnlinked(
            EventMetadata(eventType = DiscordAccountUnlinked.TYPE, sourceServerId = serverId),
            link.playerUuid,
            link.playerName,
            link.discordUserId,
        )
        try {
            bus.publish(event).whenComplete { _, failure ->
                if (failure != null) logger.log(Level.WARNING, "Discord link data was removed but the unlink event could not be published", failure)
            }
        } catch (failure: Throwable) {
            logger.log(Level.WARNING, "Discord link data was removed but the unlink event could not be published", failure)
        }
    }

    private fun <T> async(block: () -> T): CompletionStage<T> =
        CompletableFuture.supplyAsync(block, executor).exceptionallyCompose { failure ->
            val cause = unwrap(failure)
            CompletableFuture.failedFuture(
                if (cause is DiscordLinkException) cause
                else DiscordLinkException(DiscordLinkException.Code.DATABASE_FAILURE, "Discord link data could not be read or written.", cause),
            )
        }

    private fun unwrap(failure: Throwable): Throwable {
        var current = failure
        while (current is CompletionException && current.cause != null) current = current.cause!!
        return current
    }
}
