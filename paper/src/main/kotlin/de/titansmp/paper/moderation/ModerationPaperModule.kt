package de.titansmp.paper.moderation

import de.titansmp.api.event.EventBus
import de.titansmp.api.event.EventEnvelope
import de.titansmp.api.event.EventListener
import de.titansmp.api.event.EventSubscription
import de.titansmp.api.moderation.ModerationActor
import de.titansmp.api.moderation.ModerationService
import de.titansmp.api.moderation.ModerationTarget
import de.titansmp.api.moderation.PlayerBanned
import de.titansmp.api.moderation.PlayerKicked
import de.titansmp.api.moderation.PlayerMuted
import de.titansmp.api.moderation.PlayerUnbanned
import de.titansmp.api.moderation.PlayerUnmuted
import de.titansmp.api.moderation.PunishmentType
import de.titansmp.api.player.PlayerService
import de.titansmp.core.moderation.InvalidModerationDurationException
import de.titansmp.core.moderation.ModerationDurationParser
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import io.papermc.paper.event.player.AsyncChatEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.java.JavaPlugin
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

class ModerationPaperModule(
    private val plugin: JavaPlugin,
    private val service: ModerationService,
    private val players: PlayerService,
    private val eventBus: EventBus,
    private val serverId: String,
    private val asyncExecutor: Executor,
) : Listener, AutoCloseable {
    private val subscriptions = mutableListOf<EventSubscription>()
    private val muted = ConcurrentHashMap.newKeySet<UUID>()
    private val muteStatusLoaded = ConcurrentHashMap.newKeySet<UUID>()

    fun register() {
        Bukkit.getPluginManager().registerEvents(this, plugin)
        registerCommand("ban", ::ban)
        registerCommand("unban", ::unban)
        registerCommand("mute", ::mute)
        registerCommand("unmute", ::unmute)
        registerCommand("kick", ::kick)
        subscriptions += eventBus.subscribe(PlayerBanned.TYPE, EventListener(::onModerationEvent))
        subscriptions += eventBus.subscribe(PlayerUnbanned.TYPE, EventListener(::onModerationEvent))
        subscriptions += eventBus.subscribe(PlayerMuted.TYPE, EventListener(::onModerationEvent))
        subscriptions += eventBus.subscribe(PlayerUnmuted.TYPE, EventListener(::onModerationEvent))
        subscriptions += eventBus.subscribe(PlayerKicked.TYPE, EventListener(::onModerationEvent))
        Bukkit.getOnlinePlayers().forEach(::initializePlayer)
    }

    override fun close() {
        subscriptions.forEach(EventSubscription::close)
        subscriptions.clear()
        muted.clear(); muteStatusLoaded.clear()
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) = initializePlayer(event.player)

    @EventHandler
    fun onChat(event: AsyncChatEvent) {
        val id = event.player.uniqueId
        val isMuted = if (event.isAsynchronous) {
            try {
                service.isMuted(id).toCompletableFuture().join().also { current ->
                    if (current) muted += id else muted -= id
                    muteStatusLoaded += id
                }
            } catch (failure: Throwable) {
                event.isCancelled = true
                plugin.logger.severe("Could not check mute status for $id; chat blocked: ${failure.cause?.message ?: failure.message}")
                return
            }
        } else {
            id !in muteStatusLoaded || id in muted
        }
        if (isMuted) {
            event.isCancelled = true
            event.player.sendMessage(Component.text(if (id in muteStatusLoaded) "You are muted and cannot chat." else "Your mute status is still loading."))
        }
    }

    private fun initializePlayer(player: Player) {
        muteStatusLoaded -= player.uniqueId
        service.checkPunishment(player.uniqueId, PunishmentType.BAN).whenComplete { ban, banFailure ->
            if (banFailure != null) plugin.logger.severe("Could not check active ban for ${player.uniqueId}: ${banFailure.message}")
            else if (ban != null) {
                runOnMain { if (player.isOnline) player.kick(Component.text("You are banned: ${ban.reason}")) }
            }
        }
        service.isMuted(player.uniqueId).whenComplete { isMuted, failure ->
            if (failure != null) plugin.logger.severe("Could not load mute state for ${player.uniqueId}; chat remains blocked: ${failure.message}")
            else runOnMain {
                if (isMuted) muted += player.uniqueId else muted -= player.uniqueId
                muteStatusLoaded += player.uniqueId
            }
        }
    }

    private fun onModerationEvent(envelope: EventEnvelope) = when (val event = envelope.event) {
        is PlayerBanned -> {
            val expiresAt = event.expiresAt
            if (expiresAt == null || expiresAt.isAfter(Instant.now()))
                runOnMain { Bukkit.getPlayer(event.targetId)?.kick(Component.text("You are banned: ${event.reason}")) }
            Unit
        }
        is PlayerKicked -> runOnMain { Bukkit.getPlayer(event.targetId)?.kick(Component.text(event.reason)) }
        is PlayerMuted -> runOnMain {
            val expiresAt = event.expiresAt
            if (expiresAt == null || expiresAt.isAfter(Instant.now())) muted += event.targetId else muted -= event.targetId
            muteStatusLoaded += event.targetId
        }
        is PlayerUnmuted -> runOnMain { muted -= event.targetId; muteStatusLoaded += event.targetId }
        is PlayerUnbanned -> Unit
        else -> Unit
    }

    private fun ban(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) return usage(sender, "/ban <player> [reason]")
        resolveTarget(args[0], sender) { target ->
            val reason = args.drop(1).joinToString(" ").ifBlank { "No reason provided" }
            submit(sender, "Ban saved for ${target.name}.") { actor -> service.ban(actor, target, reason, serverId) }
        }
    }

    private fun unban(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) return usage(sender, "/unban <player>")
        resolveTarget(args[0], sender) { target ->
            val reason = args.drop(1).joinToString(" ").ifBlank { null }
            submit(sender, "Ban removed for ${target.name}.") { actor -> service.unban(actor, target, reason, serverId) }
        }
    }

    private fun mute(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) return usage(sender, "/mute <player> [duration] [reason]")
        val duration: java.time.Duration?
        val reason: String
        try {
            val candidate = args.getOrNull(1)
            val parsed = candidate?.let { value -> runCatching { ModerationDurationParser.parse(value) }.getOrNull() }
            val malformedDuration = candidate != null && parsed == null && (candidate.any(Char::isDigit) || candidate.equals("abc", true))
            if (malformedDuration) throw InvalidModerationDurationException(candidate)
            if (parsed != null) { duration = parsed; reason = args.drop(2).joinToString(" ").ifBlank { "No reason provided" } }
            else { duration = null; reason = args.drop(1).joinToString(" ").ifBlank { "No reason provided" } }
        } catch (failure: InvalidModerationDurationException) {
            sender.sendMessage(Component.text(failure.message ?: "Invalid duration.")); return
        }
        resolveTarget(args[0], sender) { target ->
            submit(sender, "Mute saved for ${target.name}.") { actor -> service.mute(actor, target, duration, reason, serverId) }
        }
    }

    private fun unmute(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) return usage(sender, "/unmute <player>")
        resolveTarget(args[0], sender) { target ->
            val reason = args.drop(1).joinToString(" ").ifBlank { null }
            submit(sender, "Mute removed for ${target.name}.") { actor -> service.unmute(actor, target, reason, serverId) }
        }
    }

    private fun kick(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) return usage(sender, "/kick <player> [reason]")
        resolveTarget(args[0], sender) { target ->
            asyncExecutor.execute {
                try {
                    val presence = players.findPlayer(target.uuid)
                    runOnMain {
                        if (presence?.online != true || presence.currentServer == null) {
                            sender.sendMessage(Component.text("That player is not online on the network."))
                        } else {
                            val reason = args.drop(1).joinToString(" ").ifBlank { "Kicked by a moderator" }
                            submit(sender, "Kick recorded for ${target.name}.") { actor -> service.kick(actor, target, reason, serverId) }
                        }
                    }
                } catch (failure: Throwable) {
                    plugin.logger.severe("Could not check player presence before kick: ${failure.message}")
                    runOnMain { sender.sendMessage(Component.text("Player presence is unavailable right now.")) }
                }
            }
        }
    }

    private fun resolveTarget(name: String, sender: CommandSender, callback: (ModerationTarget) -> Unit) {
        val online = Bukkit.getPlayerExact(name)
        if (online != null) { callback(ModerationTarget(online.uniqueId, online.name)); return }
        asyncExecutor.execute {
            try {
                val player = players.findPlayerByName(name)
                runOnMain {
                    if (player == null) sender.sendMessage(Component.text("Player '$name' was not found."))
                    else callback(ModerationTarget(player.uuid, player.username))
                }
            } catch (failure: Throwable) {
                plugin.logger.severe("Could not resolve moderation target '$name': ${failure.message}")
                runOnMain { sender.sendMessage(Component.text("Player data is unavailable right now.")) }
            }
        }
    }

    private fun submit(sender: CommandSender, success: String, operation: (ModerationActor) -> java.util.concurrent.CompletionStage<*>) {
        val actor = if (sender is Player) ModerationActor(sender.uniqueId, sender.name) else ModerationActor(null, sender.name)
        operation(actor).whenComplete { _, failure -> runOnMain {
            if (failure == null) sender.sendMessage(Component.text(success)) else sender.sendMessage(Component.text(failureMessage(failure)))
        } }
    }

    private fun failureMessage(failure: Throwable): String {
        var cause = failure
        while (cause.cause != null && cause !is de.titansmp.api.moderation.ModerationException) cause = cause.cause!!
        return when (cause) {
            is de.titansmp.api.moderation.ModerationException -> cause.message ?: "Moderation action failed."
            else -> { plugin.logger.severe("Moderation command failed: ${cause.message}"); "Moderation data is unavailable right now." }
        }
    }

    private fun registerCommand(name: String, execute: (CommandSender, Array<out String>) -> Unit) {
        val command = plugin.getCommand(name) ?: error("Command '$name' is missing from plugin.yml")
        command.setExecutor(CommandExecutor { sender, _, _, args -> execute(sender, args); true })
    }

    private fun usage(sender: CommandSender, value: String) { sender.sendMessage(Component.text("Usage: $value")) }
    private fun runOnMain(task: () -> Unit) { Bukkit.getScheduler().runTask(plugin, Runnable(task)) }
}
