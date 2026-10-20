package de.titansmp.paper.friends

import de.titansmp.api.event.*
import de.titansmp.api.friends.*
import de.titansmp.api.player.PlayerService
import de.titansmp.api.player.event.PlayerConnected
import de.titansmp.core.friends.JdbcFriendsRepository
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import org.bukkit.Bukkit
import org.bukkit.command.*
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor

class FriendsPaperModule(
    private val plugin: JavaPlugin,
    private val service: FriendService,
    private val repository: JdbcFriendsRepository,
    private val players: PlayerService,
    private val events: EventBus,
    private val pageSize: Int,
    private val executor: Executor,
) : CommandExecutor, AutoCloseable {
    private val subscriptions = mutableListOf<EventSubscription>()
    fun register() {
        plugin.getCommand("friends")?.setExecutor(this) ?: error("Missing /friends command registration")
        subscriptions += events.subscribe(FriendRequestCreated.TYPE, EventListener { envelope ->
            val e = envelope.event as FriendRequestCreated
            Bukkit.getPlayer(e.targetUuid)?.let { recipient ->
                val name = players.findPlayer(e.requesterUuid)?.username ?: e.requesterName.ifBlank { "A player" }
                Bukkit.getScheduler().runTask(plugin, Runnable { notifyRequest(recipient, e.requesterUuid, name) })
            }
        })
        subscriptions += events.subscribe(PlayerConnected.TYPE, EventListener { envelope ->
            val e = envelope.event as PlayerConnected
            Bukkit.getScheduler().runTask(plugin, Runnable { Bukkit.getPlayer(e.playerId)?.let { onNetworkJoin(it) } })
        })
        subscriptions += events.subscribe(FriendRequestAccepted.TYPE, EventListener { envelope ->
            val e = envelope.event as FriendRequestAccepted
            executor.execute { Bukkit.getPlayer(e.requesterUuid)?.let { target ->
                val name = players.findPlayer(e.playerUuid)?.username ?: "A player"
                Bukkit.getScheduler().runTask(plugin, Runnable { target.sendMessage(Component.text("$name accepted your friend request.")) })
            } }
        })
        subscriptions += events.subscribe(FriendRequestRejected.TYPE, EventListener { envelope ->
            val e = envelope.event as FriendRequestRejected
            executor.execute { Bukkit.getPlayer(e.requesterUuid)?.let { target ->
                val name = players.findPlayer(e.playerUuid)?.username ?: "A player"
                Bukkit.getScheduler().runTask(plugin, Runnable { target.sendMessage(Component.text("$name rejected your friend request.")) })
            } }
        })
        subscriptions += events.subscribe(FriendRemoved.TYPE, EventListener { envelope ->
            val e = envelope.event as FriendRemoved
            executor.execute { Bukkit.getPlayer(e.friendUuid)?.let { target ->
                val name = players.findPlayer(e.playerUuid)?.username ?: "A player"
                Bukkit.getScheduler().runTask(plugin, Runnable { target.sendMessage(Component.text("$name removed you as a friend.")) })
            } }
        })
    }
    override fun close() { subscriptions.forEach(EventSubscription::close); subscriptions.clear() }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = sender as? Player ?: run { sender.sendMessage(Component.text("Only players can use friends.")); return true }
        if (args.isEmpty()) { help(player); return true }
        when (args[0].lowercase()) {
            "add" -> if (args.size == 2) resolve(player, args[1]) { target -> service.sendRequest(player.uniqueId, target.uuid, player.name).whenComplete { result, error -> main(player) {
                if (error != null) fail(player, error) else when (result) {
                    FriendRequestResult.CREATED -> player.sendMessage(Component.text("Friend request sent to ${target.username}."))
                    FriendRequestResult.INCOMING_REQUEST_EXISTS -> player.sendMessage(Component.text("${target.username} already sent you a request. Use /friends requests to accept it."))
                    FriendRequestResult.ALREADY_FRIENDS -> player.sendMessage(Component.text("You are already friends with ${target.username}."))
                    FriendRequestResult.BLOCKED -> player.sendMessage(Component.text("You cannot send a friend request to that player."))
                    FriendRequestResult.SELF -> player.sendMessage(Component.text("You cannot add yourself."))
                    FriendRequestResult.DUPLICATE -> player.sendMessage(Component.text("You already sent a request to ${target.username}."))
                    null -> Unit
                }
            } } } else usage(player, "/friends add <player>")
            "accept", "reject" -> if (args.size == 2) resolve(player, args[1]) { target -> decide(player, target.uuid, args[0].equals("accept", true), target.username) } else { usage(player, "/friends ${args[0]} <player>"); true }
            "__accept", "__reject" -> if (args.size == 2) runCatching { UUID.fromString(args[1]) }.onSuccess { decide(player, it, args[0] == "__accept", "player") }.onFailure { usage(player, "/friends") } else usage(player, "/friends")
            "remove", "block", "unblock" -> if (args.size == 2) resolve(player, args[1]) { target ->
                val op = when (args[0].lowercase()) { "remove" -> service.removeFriend(player.uniqueId, target.uuid); "block" -> service.blockPlayer(player.uniqueId, target.uuid); else -> service.unblockPlayer(player.uniqueId, target.uuid) }
                op.whenComplete { changed, error -> main(player) { if (error != null) fail(player, error) else player.sendMessage(Component.text(if (changed == true) when (args[0].lowercase()) { "remove" -> "You are no longer friends with ${target.username}."; "block" -> "You blocked ${target.username}. Any friendship and pending requests between you were removed."; else -> "You unblocked ${target.username}." } else "No matching friend or block was found.")) } }
            } else { usage(player, "/friends ${args[0]} <player>"); true }
            "requests", "list" -> {
                val page = args.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 } ?: if (args.size == 1) 1 else { usage(player, "/friends ${args[0]} [page]"); return true }
                if (args.size > 2) { usage(player, "/friends ${args[0]} [page]"); return true }
                val requestList = args[0].equals("requests", true)
                val stage = if (requestList) service.getIncomingRequests(player.uniqueId, page, pageSize) else service.getFriends(player.uniqueId, page, pageSize)
                stage.whenComplete { result, error -> if (error != null) main(player) { fail(player, error) } else executor.execute {
                    val heading = if (requestList) "Friend Requests" else "Friends"
                    val lines = result.entries.map { entry ->
                        val id = if (entry is FriendRequest) entry.requesterUuid else (entry as Friend).playerUuid
                        val identity = players.findPlayer(id)
                        val name = identity?.username ?: id.toString()
                        if (requestList) Component.text("• $name ").append(button("ACCEPT", NamedTextColor.GREEN, "/friends __accept $id")).append(Component.text(" ")).append(button("REJECT", NamedTextColor.RED, "/friends __reject $id"))
                        else Component.text("$name - ").append(Component.text(if (identity?.online == true) "Online" else "Offline", if (identity?.online == true) NamedTextColor.GREEN else NamedTextColor.GRAY))
                    }
                    main(player) { player.sendMessage(Component.text("$heading (${result.page}/${result.pageCount})", NamedTextColor.GOLD)); lines.forEach(player::sendMessage) }
                } }
            }
            else -> usage(player, "/friends")
        }
        return true
    }

    private fun decide(player: Player, requester: UUID, accept: Boolean, name: String) {
        val future = if (accept) service.acceptRequest(player.uniqueId, requester) else service.rejectRequest(player.uniqueId, requester)
        future.whenComplete { changed, failure -> main(player) {
            if (failure != null) fail(player, failure) else if (changed != true) player.sendMessage(Component.text("That friend request is no longer available."))
            else {
                player.sendMessage(Component.text(if (accept) "You are now friends with $name." else "Friend request from $name rejected."))
            }
        } }
    }
    private fun onNetworkJoin(joiner: Player) {
        service.getIncomingRequests(joiner.uniqueId, 1, pageSize).whenComplete { page, failure -> if (failure == null) executor.execute {
            page.entries.forEach { request ->
                val name = players.findPlayer(request.requesterUuid)?.username ?: "A player"
                Bukkit.getScheduler().runTask(plugin, Runnable { Bukkit.getPlayer(joiner.uniqueId)?.let { notifyRequest(it, request.requesterUuid, name) } })
            }
        } }
        notifyOnlineFriends(joiner.uniqueId, joiner.name, 1)
    }
    private fun notifyOnlineFriends(joinerUuid: UUID, joinerName: String, pageNumber: Int) {
        service.getFriends(joinerUuid, pageNumber, pageSize).whenComplete { page, failure ->
            if (failure != null) return@whenComplete
            page.entries.forEach { friend ->
                service.isBlocked(friend.playerUuid, joinerUuid).whenComplete { blocked, _ ->
                    if (blocked != true) Bukkit.getScheduler().runTask(plugin, Runnable { Bukkit.getPlayer(friend.playerUuid)?.sendMessage(Component.text("Your friend $joinerName joined the network.")) })
                }
            }
            if (page.page < page.pageCount) notifyOnlineFriends(joinerUuid, joinerName, pageNumber + 1)
        }
    }
    private fun notifyRequest(recipient: Player, requester: UUID, name: String) {
        recipient.sendMessage(Component.text("$name sent you a friend request."))
        recipient.sendMessage(button("[ ACCEPT ]", NamedTextColor.GREEN, "/friends __accept $requester").append(Component.text(" ")).append(button("[ REJECT ]", NamedTextColor.RED, "/friends __reject $requester")))
    }
    private fun button(label: String, color: NamedTextColor, command: String) = Component.text(label, color).clickEvent(ClickEvent.runCommand(command)).hoverEvent(HoverEvent.showText(Component.text("Click to respond")))
    private fun resolve(player: Player, name: String, action: (de.titansmp.api.player.NetworkPlayer) -> Unit) { executor.execute {
        val target = runCatching { players.findPlayerByName(name) }.getOrNull()
        Bukkit.getScheduler().runTask(plugin, Runnable { if (target == null) player.sendMessage(Component.text("Player '$name' was not found.")) else action(target) })
    } }
    private fun help(player: Player) { listOf("/friends add <player>", "/friends accept|reject <player>", "/friends remove|block|unblock <player>", "/friends requests [page]", "/friends list [page]").forEach { player.sendMessage(Component.text(it, NamedTextColor.YELLOW)) } }
    private fun usage(player: Player, text: String) { player.sendMessage(Component.text("Usage: $text", NamedTextColor.YELLOW)) }
    private fun main(player: Player, action: () -> Unit) { Bukkit.getScheduler().runTask(plugin, Runnable { if (player.isOnline) action() }) }
    private fun fail(player: Player, failure: Throwable) { player.sendMessage(Component.text("Friends request failed: ${unwrap(failure).message ?: "unknown error"}", NamedTextColor.RED)) }
    private fun unwrap(error: Throwable): Throwable = if (error is CompletionException && error.cause != null) error.cause!! else error
}
