package de.titansmp.velocity.servertransfer

import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import de.titansmp.api.servertransfer.ServerTransferFailure
import de.titansmp.api.servertransfer.ServerTransferResult
import de.titansmp.api.servertransfer.ServerTransferService
import de.titansmp.core.config.ServerTransferConfig
import de.titansmp.core.servertransfer.ServerTransferAliases
import de.titansmp.core.servertransfer.ServerTransferCommandSurface
import net.kyori.adventure.text.Component
import org.slf4j.Logger

/**
 * Registers `/join`, `/lobby`, and `/hub` only. The native Velocity `/server` command and its
 * `velocity.command.server` permission are intentionally left untouched.
 */
class VelocityServerTransferCommands(
    private val proxy: ProxyServer,
    private val plugin: Any,
    private val service: ServerTransferService,
    private val config: ServerTransferConfig,
    private val logger: Logger,
) {
    fun register() {
        registerServerTypeCommand(ServerTransferCommandSurface.JOIN, requiresArgument = true)
        registerServerTypeCommand(ServerTransferCommandSurface.LOBBY, requiresArgument = false)
        registerServerTypeCommand(ServerTransferCommandSurface.HUB, requiresArgument = false)
    }

    private fun registerServerTypeCommand(name: String, requiresArgument: Boolean) {
        val meta = proxy.commandManager.metaBuilder(name).plugin(plugin).build()
        proxy.commandManager.register(meta, object : SimpleCommand {
            override fun hasPermission(invocation: SimpleCommand.Invocation): Boolean = true

            override fun execute(invocation: SimpleCommand.Invocation) {
                val source = invocation.source()
                if (source !is Player) {
                    source.sendMessage(Component.text("Only players can use this command."))
                    return
                }
                if (!config.enabled) {
                    source.sendMessage(Component.text("Server transfers are disabled."))
                    return
                }
                val arguments = invocation.arguments()
                if (requiresArgument) {
                    if (arguments.size != 1) {
                        source.sendMessage(Component.text("Usage: /join <server-type>"))
                        return
                    }
                    val type = arguments[0].trim().lowercase()
                    if (!config.isEnabledType(type)) {
                        source.sendMessage(Component.text("Unknown server type. Use /join <server-type>."))
                        return
                    }
                    requestJoin(source, type)
                } else {
                    if (arguments.isNotEmpty()) {
                        source.sendMessage(Component.text("Usage: /$name"))
                        return
                    }
                    val type = ServerTransferAliases.resolve(config, name)
                    if (type == null) {
                        source.sendMessage(Component.text("This server-transfer alias is not configured."))
                        return
                    }
                    requestJoin(source, type)
                }
            }

            override fun suggest(invocation: SimpleCommand.Invocation): List<String> =
                if (requiresArgument) {
                    config.enabledTypes().filter { it.startsWith(invocation.arguments().lastOrNull().orEmpty()) }
                } else {
                    emptyList()
                }
        })
    }

    private fun requestJoin(player: Player, serverType: String) {
        service.requestJoin(player.uniqueId, serverType).whenComplete { result, failure ->
            if (failure != null) {
                logger.error("Server transfer request failed for {}", player.uniqueId, failure)
                player.sendMessage(Component.text("The server transfer could not be completed."))
                return@whenComplete
            }
            when (result) {
                is ServerTransferResult.Connected ->
                    player.sendMessage(Component.text("Connecting you to ${result.serverType}."))
                is ServerTransferResult.Queued ->
                    player.sendMessage(
                        Component.text(
                            "You are position ${result.position} of ${result.size} in the ${result.serverType} queue.",
                        ),
                    )
                is ServerTransferResult.AlreadyConnected ->
                    player.sendMessage(Component.text("You are already on a ${result.serverType} server."))
                is ServerTransferResult.Failed -> player.sendMessage(Component.text(failureMessage(result.reason)))
            }
        }
    }

    private fun failureMessage(failure: ServerTransferFailure): String = when (failure) {
        ServerTransferFailure.DISABLED -> "Server transfers are disabled."
        ServerTransferFailure.UNKNOWN_SERVER_TYPE -> "Unknown server type."
        ServerTransferFailure.TARGET_NOT_FOUND -> "That server is not available."
        ServerTransferFailure.TARGET_FULL -> "That server is full."
        ServerTransferFailure.PLAYER_NOT_CONNECTED -> "You are not connected to the network."
        ServerTransferFailure.TRANSFER_IN_PROGRESS -> "A server transfer is already in progress. Please wait."
        ServerTransferFailure.QUEUE_UNAVAILABLE -> "The server queue is temporarily unavailable."
        ServerTransferFailure.CONNECTION_REJECTED -> "The server rejected the connection."
        ServerTransferFailure.INTERNAL_ERROR -> "The server transfer could not be completed."
    }
}
