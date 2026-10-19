package de.titansmp.discord

import de.titansmp.api.display.DisplayProvider
import de.titansmp.api.display.PlayerDisplayMeta
import de.titansmp.api.moderation.ActivePunishment
import de.titansmp.api.moderation.ModerationActor
import de.titansmp.api.moderation.ModerationPermissionService
import de.titansmp.api.moderation.ModerationService
import de.titansmp.api.moderation.ModerationTarget
import de.titansmp.api.moderation.PunishmentType
import de.titansmp.chat.DefaultChatService
import de.titansmp.core.config.TitanSmpConfigLoader
import de.titansmp.core.display.DefaultPlayerDisplayService
import de.titansmp.core.discord.link.DefaultDiscordLinkService
import de.titansmp.core.discord.link.JdbcDiscordLinkRepository
import de.titansmp.core.lifecycle.TitanSmpRuntime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.logging.Level
import java.util.logging.Logger

private val logger = Logger.getLogger("de.titansmp.discord")

/**
 * Entry point of the dedicated Discord runtime.
 *
 * It is its own runtime next to Paper, Velocity, and the web API, so exactly one instance owns the
 * Discord gateway; no Paper server ever starts a bot. All shared infrastructure (MariaDB pool,
 * Flyway migrations, Redis event bus) comes from [TitanSmpRuntime].
 *
 * Startup is intentionally fail-fast and non-silent: a missing or invalid configuration, an
 * unreachable guild, a missing channel, or a missing role each log a clear message and stop the
 * process instead of running a partial bridge.
 */
fun main() {
    val config = try {
        TitanSmpConfigLoader().load()
    } catch (failure: Throwable) {
        logger.log(Level.SEVERE, "The Discord bot configuration could not be loaded: ${failure.message}", failure)
        return
    }

    if (!config.discord.enabled) {
        logger.info("The Discord integration is disabled (discord.enabled=false); the Discord bot will not start.")
        return
    }

    val settings = try {
        config.discord.requireBotSettings()
    } catch (failure: Throwable) {
        logger.log(Level.SEVERE, "The Discord bot configuration is incomplete: ${failure.message}", failure)
        return
    }

    val runtime = TitanSmpRuntime()
    var bot: DiscordBot? = null
    var databaseExecutor: ExecutorService? = null
    try {
        val server = runtime.start()
        val eventBus = runtime.eventBus()
        val databaseExecutorService = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "titansmp-discord-db").apply { isDaemon = true }
        }
        databaseExecutor = databaseExecutorService

        val links = DefaultDiscordLinkService(
            repository = JdbcDiscordLinkRepository(runtime.databaseDataSource()),
            eventBus = eventBus,
            serverId = server.identity.id,
            linking = config.discord.linking,
            executor = databaseExecutorService,
        )

        // The bot has no LuckPerms instance, so it only knows display data the Minecraft servers have
        // published on the event bus. Until a prefix is known the message renders without one, which is
        // exactly the fallback the chat renderer expects.
        val display = DefaultPlayerDisplayService(
            provider = DisplayProvider { playerUuid -> CompletableFuture.completedFuture(PlayerDisplayMeta(playerUuid)) },
            eventBus = eventBus,
            serverId = server.identity.id,
            logger = logger,
        )
        val chat = DefaultChatService(eventBus, NoopModerationService, NoopModerationPermissionService, display, config.chat)

        val discordBot = DiscordBot(settings, links, chat, display, eventBus, server.identity.id, config.chat.maxMessageLength)
        bot = discordBot
        discordBot.start()

        Runtime.getRuntime().addShutdownHook(Thread { discordBot.close() })
        logger.info("TitanSMP Discord bot is running")
        discordBot.awaitShutdown()
    } catch (failure: Throwable) {
        logger.log(Level.SEVERE, "The Discord bot stopped: ${failure.message}", failure)
    } finally {
        try {
            bot?.close()
        } catch (failure: Throwable) {
            logger.log(Level.WARNING, "The Discord bot could not be closed cleanly", failure)
        }
        try {
            runtime.close()
        } catch (failure: Throwable) {
            logger.log(Level.WARNING, "The TitanSMP runtime could not be closed cleanly", failure)
        }
        databaseExecutor?.shutdownNow()
    }
}

/**
 * The Discord runtime never moderates: it only broadcasts already-validated Discord messages into the
 * network chat. The shared [ChatService] constructor needs these collaborators, so they are no-ops
 * here rather than a second implementation of moderation.
 */
private object NoopModerationService : ModerationService {
    private fun unsupported(): Nothing = throw UnsupportedOperationException("The Discord runtime does not moderate")

    override fun ban(actor: ModerationActor, target: ModerationTarget, reason: String, serverId: String): CompletionStage<ActivePunishment> = unsupported()
    override fun unban(actor: ModerationActor, target: ModerationTarget, reason: String?, serverId: String): CompletionStage<Unit> = unsupported()
    override fun mute(actor: ModerationActor, target: ModerationTarget, duration: java.time.Duration?, reason: String, serverId: String): CompletionStage<ActivePunishment> = unsupported()
    override fun unmute(actor: ModerationActor, target: ModerationTarget, reason: String?, serverId: String): CompletionStage<Unit> = unsupported()
    override fun kick(actor: ModerationActor, target: ModerationTarget, reason: String, serverId: String): CompletionStage<Unit> = unsupported()
    override fun checkPunishment(targetId: UUID, type: PunishmentType): CompletionStage<ActivePunishment?> =
        CompletableFuture.completedFuture(null)
}

private object NoopModerationPermissionService : ModerationPermissionService {
    override fun hasPermission(playerId: UUID, permission: String): CompletionStage<Boolean> = CompletableFuture.completedFuture(true)
}
