package de.titansmp.paper.discord

import de.titansmp.api.discord.DiscordLinkException
import de.titansmp.api.discord.DiscordLinkService
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor

/**
 * Minecraft side of the Discord account link.
 *
 * `/link` asks the shared [DiscordLinkService] for a single-use, high-entropy, time-limited code and
 * shows it once; only a hash of it is stored. `/unlink` removes the caller's own link and can never
 * touch another player's link because the lookup key is always the caller's UUID.
 *
 * The bot owns everything Discord-side. Both commands work for every player and need no special
 * Discord role: the identity of a `/link` is the Discord account that redeems the code.
 *
 * The module only registers when `discord.enabled=true`, so a server without the Discord feature keeps
 * a consistent plugin.yml and simply reports that linking is not available.
 */
class DiscordLinkPaperModule(
    private val plugin: JavaPlugin,
    private val service: DiscordLinkService,
    private val executor: Executor,
    private val tokenExpirationMinutes: Long,
) : AutoCloseable {

    fun register() {
        plugin.getCommand("link")?.setExecutor { sender, _, _, _ ->
            onLink(sender)
            true
        } ?: plugin.logger.warning("/link is not declared in plugin.yml")
        plugin.getCommand("unlink")?.setExecutor { sender, _, _, _ ->
            onUnlink(sender)
            true
        } ?: plugin.logger.warning("/unlink is not declared in plugin.yml")
    }

    override fun close() = Unit

    private fun onLink(sender: CommandSender) {
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage(Component.text("Only players can link a Discord account."))
            return
        }
        val playerUuid = player.uniqueId
        val playerName = player.name
        executor.execute {
            service.createLinkToken(playerUuid, playerName).whenComplete { token, failure ->
                val message = if (failure == null) {
                    "Your Discord link code is ${token.code}\n" +
                        "Enter it with /link <code> in the Discord chat channel. " +
                        "The code can be used once and expires in $tokenExpirationMinutes minutes."
                } else {
                    linkFailureMessage(unwrap(failure))
                }
                deliver(player, message)
            }
        }
    }

    private fun onUnlink(sender: CommandSender) {
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage(Component.text("Only players can unlink a Discord account."))
            return
        }
        val playerUuid = player.uniqueId
        executor.execute {
            service.unlinkByPlayer(playerUuid).whenComplete { removed, failure ->
                val message = when {
                    failure != null -> {
                        plugin.logger.warning("Player $playerUuid could not unlink their Discord account: ${unwrap(failure).message}")
                        "Your Discord account could not be unlinked right now. Please try again later."
                    }
                    removed == null -> "Your Minecraft account is not linked to a Discord account."
                    else -> "Your Discord account has been unlinked."
                }
                deliver(player, message)
            }
        }
    }

    private fun deliver(player: Player, message: String) {
        Bukkit.getScheduler().runTask(plugin, Runnable { if (player.isOnline) player.sendMessage(Component.text(message)) })
    }

    private fun linkFailureMessage(failure: Throwable): String = when ((failure as? DiscordLinkException)?.code) {
        DiscordLinkException.Code.ALREADY_LINKED_PLAYER ->
            "Your Minecraft account is already linked to a Discord account. Use /unlink first."

        else -> {
            plugin.logger.warning("A Discord link code could not be created: ${failure.message}")
            "Your link code could not be created right now. Please try again later."
        }
    }

    private fun unwrap(failure: Throwable): Throwable {
        var current = failure
        while (current is CompletionException && current.cause != null) current = current.cause!!
        return current
    }
}
