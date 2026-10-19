package de.titansmp.discord

import de.titansmp.core.config.DiscordBotSettings
import de.titansmp.core.discord.chat.DiscordChatRelay
import de.titansmp.core.discord.command.DiscordLinkCommandHandler
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.commands.OptionType
import java.util.logging.Level
import java.util.logging.Logger

/**
 * The only JDA listener of the Discord bot. It turns gateway events into calls on the
 * platform-independent `modules:discord` classes and never contains linking or chat rules itself.
 *
 * - [onMessageReceived] forwards human messages of the configured channel to Minecraft through
 *   [DiscordChatRelay]. Messages authored by a bot are ignored by the relay, so the bot's own
 *   [onMessageReceived] output can never echo back into Minecraft.
 * - [onSlashCommandInteraction] implements `/link <code>` and `/unlink`. Both replies are ephemeral so
 *   link codes and error messages stay private, and both are deferred because the database and role
 *   work run asynchronously.
 */
class DiscordGatewayListener(
    private val settings: DiscordBotSettings,
    private val relay: DiscordChatRelay,
    private val commands: DiscordLinkCommandHandler,
    private val logger: Logger = Logger.getLogger(DiscordGatewayListener::class.java.name),
) : ListenerAdapter() {

    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (!event.isFromGuild) return
        if (!relay.acceptsInbound(event.guild.idLong, event.channel.idLong, event.author.isBot)) return
        relay.relayInbound(event.author.idLong, event.message.contentDisplay)
            .handle { _, failure ->
                if (failure != null) logger.log(Level.WARNING, "A Discord chat message could not be forwarded to Minecraft", failure)
            }
    }

    override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
        if (event.guild?.idLong != settings.guildId) return

        val stage = when (event.name) {
            DiscordBotCommands.LINK -> {
                val code = event.getOption(DiscordBotCommands.LINK_CODE_OPTION)?.asString
                if (code.isNullOrBlank()) {
                    event.reply("Please provide the link code shown by /link in Minecraft.").setEphemeral(true).queue()
                    return
                }
                commands.link(event.user.idLong, code)
            }
            DiscordBotCommands.UNLINK -> commands.unlink(event.user.idLong)
            else -> return
        }

        event.deferReply(true).queue()
        stage.handle { result, failure ->
            val reply = if (failure == null) {
                result.message
            } else {
                logger.log(Level.WARNING, "A Discord slash command could not be processed", failure)
                "The command could not be processed right now. Please try again later."
            }
            event.hook.sendMessage(reply).queue()
        }
    }
}
