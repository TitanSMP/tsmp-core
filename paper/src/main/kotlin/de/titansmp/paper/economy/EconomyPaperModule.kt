package de.titansmp.paper.economy

import de.titansmp.api.economy.EconomyBalanceChanged
import de.titansmp.api.economy.EconomyBalanceEntry
import de.titansmp.api.economy.EconomyChange
import de.titansmp.api.economy.EconomyException
import de.titansmp.api.economy.EconomyReason
import de.titansmp.api.economy.EconomyService
import de.titansmp.api.economy.Money
import de.titansmp.api.economy.MoneyFormatException
import de.titansmp.api.economy.MoneyOverflowException
import de.titansmp.api.economy.TitanCurrency
import de.titansmp.api.event.EventBus
import de.titansmp.api.event.EventEnvelope
import de.titansmp.api.event.EventListener
import de.titansmp.api.event.EventSubscription
import de.titansmp.api.player.PlayerService
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor

/**
 * Paper command surface for the shared economy service.
 *
 * Commands resolve targets through the shared PlayerService (so offline identities work for
 * `/balance <player>` and `/eco`), while `/pay` additionally relies on the service's network-wide
 * presence check. Every JDBC and LuckPerms call runs on the injected worker executor; Bukkit API
 * calls are marshalled back to the server thread.
 */
class EconomyPaperModule(
    private val plugin: JavaPlugin,
    private val service: EconomyService,
    private val players: PlayerService,
    private val eventBus: EventBus,
    private val serverId: String,
    private val asyncExecutor: Executor,
) : CommandExecutor, AutoCloseable {
    private val subscriptions = mutableListOf<EventSubscription>()

    fun register() {
        registerCommand("balance")
        registerCommand("pay")
        registerCommand("balancetop")
        registerCommand("eco")
        subscriptions += eventBus.subscribe(EconomyBalanceChanged.TYPE, EventListener(::onBalanceChanged))
    }

    override fun close() {
        subscriptions.forEach(EventSubscription::close)
        subscriptions.clear()
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        when (command.name.lowercase()) {
            "balance" -> balance(sender, args)
            "pay" -> pay(sender, args)
            "balancetop" -> balancetop(sender, args)
            "eco" -> eco(sender, args)
            else -> return false
        }
        return true
    }

    private fun balance(sender: CommandSender, args: Array<out String>) {
        if (args.size > 1) return usage(sender, "/balance [player]")
        val requester = (sender as? Player)?.uniqueId
        if (args.isEmpty()) {
            if (requester == null) return usage(sender, "/balance <player>")
            service.getBalanceFor(requester, requester).whenComplete { balance, failure ->
                runOnMain {
                    if (failure == null) sender.sendMessage(Component.text("Your balance: ${balance.format()} ${TitanCurrency.DISPLAY_NAME}"))
                    else sendFailure(sender, failure)
                }
            }
            return
        }
        resolveTarget(sender, args[0]) { target ->
            service.getBalanceFor(requester, target.uuid).whenComplete { balance, failure ->
                runOnMain {
                    if (failure == null) sender.sendMessage(Component.text("${target.name}'s balance: ${balance.format()} ${TitanCurrency.DISPLAY_NAME}"))
                    else sendFailure(sender, failure)
                }
            }
        }
    }

    private fun pay(sender: CommandSender, args: Array<out String>) {
        if (args.size != 2) return usage(sender, "/pay <player> <amount>")
        val player = sender as? Player ?: return sender.sendMessage(Component.text("Only a player can send TitanCoins."))
        val amount = parseAmount(sender, args[1]) ?: return
        resolveTarget(sender, args[0]) { target ->
            service.pay(player.uniqueId, target.uuid, amount, serverId).whenComplete { _, failure ->
                runOnMain {
                    if (failure == null) player.sendMessage(Component.text("You sent ${target.name} ${amount.format()} ${TitanCurrency.DISPLAY_NAME}."))
                    else sendFailure(player, failure)
                }
            }
        }
    }

    private fun balancetop(sender: CommandSender, args: Array<out String>) {
        if (args.isNotEmpty()) return usage(sender, "/balancetop")
        val requester = (sender as? Player)?.uniqueId
        service.topBalances(requester).whenComplete { entries, failure ->
            runOnMain {
                if (failure == null) sender.sendMessage(Component.text(renderTop(entries)))
                else sendFailure(sender, failure)
            }
        }
    }

    private fun eco(sender: CommandSender, args: Array<out String>) {
        if (args.size != 3) return usage(sender, "/eco <give|take|set> <player> <amount>")
        val action = args[0].lowercase()
        if (action != "give" && action != "take" && action != "set") {
            return usage(sender, "/eco <give|take|set> <player> <amount>")
        }
        val amount = parseAmount(sender, args[2]) ?: return
        resolveTarget(sender, args[1]) { target ->
            val actor = (sender as? Player)?.uniqueId
            val operation: CompletionStage<EconomyChange> = when (action) {
                "give" -> service.adminGive(target.uuid, amount, actor, serverId)
                "take" -> service.adminTake(target.uuid, amount, actor, serverId)
                else -> service.adminSet(target.uuid, amount, actor, serverId)
            }
            operation.whenComplete { change, failure ->
                runOnMain {
                    if (failure != null) sendFailure(sender, failure)
                    else sender.sendMessage(Component.text(adminSuccess(action, target.name, amount, change)))
                }
            }
        }
    }

    private fun adminSuccess(action: String, targetName: String, amount: Money, change: EconomyChange): String {
        val currency = TitanCurrency.DISPLAY_NAME
        return when (action) {
            "give" -> "Gave $targetName ${amount.format()} $currency. New balance: ${change.newBalance.format()}."
            "take" -> "Took ${amount.format()} $currency from $targetName. New balance: ${change.newBalance.format()}."
            else -> "Set $targetName's balance to ${change.newBalance.format()} $currency."
        }
    }

    private fun parseAmount(sender: CommandSender, input: String): Money? = try {
        Money.of(input)
    } catch (failure: MoneyFormatException) {
        sender.sendMessage(Component.text(failure.message ?: "Invalid amount."))
        null
    } catch (failure: MoneyOverflowException) {
        sender.sendMessage(Component.text("That amount is too large for the economy."))
        null
    }

    private fun resolveTarget(sender: CommandSender, name: String, callback: (Target) -> Unit) {
        val online = Bukkit.getPlayerExact(name)
        if (online != null) {
            callback(Target(online.uniqueId, online.name))
            return
        }
        asyncExecutor.execute {
            try {
                val player = players.findPlayerByName(name)
                runOnMain {
                    if (player == null) sender.sendMessage(Component.text("Player '$name' was not found."))
                    else callback(Target(player.uuid, player.username))
                }
            } catch (failure: Throwable) {
                plugin.logger.severe("Could not resolve economy target '$name': ${failure.message}")
                runOnMain { sender.sendMessage(Component.text("Player data is unavailable right now.")) }
            }
        }
    }

    internal fun onBalanceChanged(envelope: EventEnvelope) {
        val event = envelope.event as? EconomyBalanceChanged ?: return
        // Only incoming payments need a notification. The sender's own command already confirms the
        // debit, and admin changes are confirmed on the command issuer's server.
        if (event.reason != EconomyReason.PAYMENT || event.newBalance <= event.oldBalance) return
        runOnMain {
            val player = Bukkit.getPlayer(event.playerUuid) ?: return@runOnMain
            val source = event.counterpartyName ?: event.counterpartyUuid?.toString() ?: "another player"
            player.sendMessage(Component.text("You received ${event.amount.format()} ${TitanCurrency.DISPLAY_NAME} from $source."))
        }
    }

    internal fun renderTop(entries: List<EconomyBalanceEntry>): String {
        if (entries.isEmpty()) return "TitanSMP BalanceTop\n\nNo balances yet."
        return buildString {
            append("TitanSMP BalanceTop\n\n")
            entries.forEach { entry ->
                append("${entry.rank}. ")
                append(entry.username.padEnd(16))
                append(" ")
                append(entry.balance.format().padStart(14))
                append('\n')
            }
        }.trimEnd()
    }

    private fun sendFailure(sender: CommandSender, failure: Throwable) {
        val cause = unwrap(failure)
        if (cause is EconomyException) {
            sender.sendMessage(Component.text(cause.message ?: "The economy could not complete this action."))
        } else {
            plugin.logger.severe("Economy command failed: ${cause.message}")
            sender.sendMessage(Component.text("Economy data is unavailable right now."))
        }
    }

    private fun unwrap(failure: Throwable): Throwable {
        var current = failure
        while ((current is CompletionException || current is ExecutionException) && current.cause != null) {
            current = current.cause!!
        }
        return current
    }

    private fun registerCommand(name: String) {
        requireNotNull(plugin.getCommand(name)) { "Command '$name' must be declared in plugin.yml" }.setExecutor(this)
    }

    private fun usage(sender: CommandSender, value: String) {
        sender.sendMessage(Component.text("Usage: $value"))
    }

    private fun runOnMain(task: () -> Unit) {
        Bukkit.getScheduler().runTask(plugin, Runnable(task))
    }

    private data class Target(val uuid: UUID, val name: String)
}
