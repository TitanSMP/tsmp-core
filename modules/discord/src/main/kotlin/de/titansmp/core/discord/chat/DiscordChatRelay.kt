package de.titansmp.core.discord.chat

import de.titansmp.api.chat.ChatMessage
import de.titansmp.api.chat.ChatMessageSource
import de.titansmp.api.chat.ChatService
import de.titansmp.api.display.PlayerDisplayService
import de.titansmp.api.discord.DiscordLinkService
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.logging.Level
import java.util.logging.Logger

/** Result of an inbound Discord message. */
sealed interface DiscordRelayResult {
    /** The message was forwarded to Minecraft as a `DISCORD` chat message. */
    data object Relayed : DiscordRelayResult

    /** The author has no Minecraft link and cannot forward messages. */
    data object NotVerified : DiscordRelayResult

    /** The message was empty or could not be forwarded. */
    data object Ignored : DiscordRelayResult
}

/**
 * Platform-independent bridge between the Discord channel and the shared network chat.
 *
 * Outbound (Minecraft -> Discord): [toDiscord] returns the Discord line for a Minecraft message, or
 * `null` when the message must not be relayed. Messages whose source is `DISCORD` (or any non-Minecraft
 * source) are dropped, which prevents an echo loop.
 *
 * Inbound (Discord -> Minecraft): [acceptsInbound] only admits human messages in the configured guild
 * and channel, and [relayInbound] resolves the author's Minecraft link, builds a
 * [ChatMessageSource.DISCORD] [ChatMessage], and broadcasts it through the shared [ChatService]. An
 * unverified author is never given a fabricated Minecraft identity.
 */
class DiscordChatRelay(
    private val chat: ChatService,
    private val links: DiscordLinkService,
    private val display: PlayerDisplayService?,
    private val guildId: Long,
    private val chatChannelId: Long,
    private val serverId: String,
    private val maxMessageLength: Int = 256,
    private val formatter: DiscordChatFormatter = DiscordChatFormatter.DEFAULT,
    private val clock: Clock = Clock.systemUTC(),
    private val logger: Logger = Logger.getLogger(DiscordChatRelay::class.java.name),
) {
    /** Returns the Discord text for a Minecraft message, or `null` when it must not be relayed. */
    fun toDiscord(message: ChatMessage): String? =
        if (message.source == ChatMessageSource.MINECRAFT) formatter.render(message) else null

    /**
     * Inbound eligibility: only the configured guild and channel, and never bot-authored messages. The
     * bot's own posts are therefore ignored and cannot echo back into Minecraft.
     */
    fun acceptsInbound(guildId: Long, channelId: Long, isBot: Boolean): Boolean =
        !isBot && guildId == this.guildId && channelId == this.chatChannelId

    /** Resolves the author's link and broadcasts a `DISCORD` chat message, or reports why it was skipped. */
    fun relayInbound(discordUserId: Long, content: String): CompletionStage<DiscordRelayResult> {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return CompletableFuture.completedFuture(DiscordRelayResult.Ignored)
        val text = if (trimmed.length > maxMessageLength) trimmed.take(maxMessageLength) else trimmed
        return links.findByDiscord(discordUserId).thenCompose { link ->
            if (link == null) return@thenCompose CompletableFuture.completedFuture(DiscordRelayResult.NotVerified)
            prefix(link.playerUuid).thenCompose { prefix ->
                val message = ChatMessage(
                    messageId = UUID.randomUUID(),
                    senderUuid = link.playerUuid,
                    senderName = link.playerName,
                    message = text,
                    serverId = serverId,
                    timestamp = clock.instant(),
                    source = ChatMessageSource.DISCORD,
                    prefix = prefix,
                )
                chat.broadcast(message).handle { _, failure ->
                    if (failure != null) {
                        logger.log(Level.WARNING, "A Discord message could not be broadcast to Minecraft", failure)
                        DiscordRelayResult.Ignored
                    } else {
                        DiscordRelayResult.Relayed
                    }
                }
            }
        }
    }

    private fun prefix(playerUuid: UUID): CompletionStage<String> {
        val service = display ?: return CompletableFuture.completedFuture("")
        return service.displayMeta(playerUuid)
            .handle { meta, failure -> if (failure != null || meta == null) "" else meta.prefix }
    }
}
