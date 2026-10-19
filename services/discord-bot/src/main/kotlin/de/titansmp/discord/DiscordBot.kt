package de.titansmp.discord

import de.titansmp.api.chat.ChatService
import de.titansmp.api.chat.NetworkChatMessage
import de.titansmp.api.discord.DiscordLinkService
import de.titansmp.api.display.PlayerDisplayService
import de.titansmp.api.event.EventBus
import de.titansmp.api.event.EventListener
import de.titansmp.api.event.EventSubscription
import de.titansmp.core.config.DiscordBotSettings
import de.titansmp.core.discord.chat.DiscordChatRelay
import de.titansmp.core.discord.command.DiscordLinkCommandHandler
import de.titansmp.core.discord.role.DiscordUnlinkRoleListener
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.requests.GatewayIntent
import java.util.logging.Level
import java.util.logging.Logger

/** Raised when the bot cannot run with the configured guild, chat channel, or verified role. */
class DiscordStartupException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * Lifecycle owner of the dedicated Discord runtime.
 *
 * [start] connects the gateway, verifies that every configured Discord ID actually resolves in the
 * configured guild, and only then wires the two bridges. A guild, channel, or role that does not
 * resolve raises [DiscordStartupException] so the runtime fails cleanly instead of starting half a
 * bridge; the caller closes the runtime and exits.
 *
 * Bridges:
 * - Minecraft -> Discord: a [NetworkChatMessage] listener forwards every Minecraft message to the
 *   configured channel. Messages that are not of source `MINECRAFT` are dropped by the relay, so the
 *   bot never re-sends a Discord-originated message.
 * - Discord -> Minecraft: [DiscordGatewayListener] forwards human messages of the configured channel
 *   into the shared `ChatService`, which publishes them on the Redis event bus.
 */
class DiscordBot(
    private val settings: DiscordBotSettings,
    private val links: DiscordLinkService,
    private val chat: ChatService,
    private val display: PlayerDisplayService?,
    private val eventBus: EventBus,
    private val serverId: String,
    private val maxMessageLength: Int,
    private val logger: Logger = Logger.getLogger(DiscordBot::class.java.name),
) : AutoCloseable {

    @Volatile
    private var jda: JDA? = null
    private var chatSubscription: EventSubscription? = null
    private var unlinkListener: DiscordUnlinkRoleListener? = null

    /** Connects the gateway and publishes both bridges. Never leaves a half-connected bot running. */
    fun start() {
        val jda = connect()
        this.jda = jda

        val guild = jda.getGuildById(settings.guildId)
            ?: throw DiscordStartupException(
                "The bot is not a member of the configured guild (discord.guild-id=${settings.guildId}). " +
                    "Invite the bot and check the configuration.",
            )
        val chatChannel = guild.getTextChannelById(settings.minecraftChatChannelId)
            ?: throw DiscordStartupException(
                "discord.minecraft-chat-channel-id is not a text channel of the configured guild.",
            )
        guild.getRoleById(settings.verifiedRoleId)
            ?: throw DiscordStartupException("discord.verified-role-id is not a role of the configured guild.")

        val relay = DiscordChatRelay(
            chat = chat,
            links = links,
            display = display,
            guildId = settings.guildId,
            chatChannelId = settings.minecraftChatChannelId,
            serverId = serverId,
            maxMessageLength = maxMessageLength,
            logger = logger,
        )
        val roles = JdaDiscordRoleNotifier(guild, settings.verifiedRoleId)

        chatSubscription = eventBus.subscribe(NetworkChatMessage.TYPE, EventListener { envelope ->
            val event = envelope.event as? NetworkChatMessage ?: return@EventListener
            relay.toDiscord(event.message)?.let { line -> sendToDiscord(chatChannel, line) }
        })
        unlinkListener = DiscordUnlinkRoleListener(eventBus, roles, serverId, logger)
        jda.addEventListener(DiscordGatewayListener(settings, relay, DiscordLinkCommandHandler(links, roles, logger), logger))

        guild.updateCommands()
            .addCommands(*DiscordBotCommands.definitions().toTypedArray())
            .queue(
                { logger.info("Discord slash commands registered: /link, /unlink") },
                { failure -> logger.log(Level.SEVERE, "Discord slash commands could not be registered", failure) },
            )

        logger.info(
            "Discord bot connected to the configured guild and chat channel " +
                "(chat channel id ${settings.minecraftChatChannelId}); the verified role is configured.",
        )
    }

    /** Blocks the calling thread until the gateway shuts down, so [main] does not exit early. */
    fun awaitShutdown() {
        jda?.awaitShutdown()
    }

    override fun close() {
        unlinkListener?.let { listener -> closeQuietly("unlink listener", listener::close) }
        unlinkListener = null
        chatSubscription?.let { subscription -> closeQuietly("chat subscription", subscription::close) }
        chatSubscription = null
        jda?.let { instance ->
            logger.info("Shutting down the Discord bot")
            instance.shutdown()
        }
        jda = null
    }

    private fun connect(): JDA = try {
        // MESSAGE_CONTENT is privileged and must be enabled for the bot application in the Discord
        // developer portal; without it the chat bridge cannot read message text.
        JDABuilder
            .createLight(settings.token, GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
            .build()
            .also { it.awaitReady() }
    } catch (failure: Throwable) {
        throw DiscordStartupException("The Discord bot could not connect to Discord.", failure)
    }

    private fun sendToDiscord(channel: TextChannel, line: String) {
        channel.sendMessage(line).queue(
            {},
            { failure -> logger.log(Level.WARNING, "A Minecraft chat message could not be sent to Discord", failure) },
        )
    }

    private fun closeQuietly(name: String, close: () -> Unit) {
        try {
            close()
        } catch (failure: Throwable) {
            logger.log(Level.WARNING, "The Discord bot's $name could not be closed cleanly", failure)
        }
    }
}
