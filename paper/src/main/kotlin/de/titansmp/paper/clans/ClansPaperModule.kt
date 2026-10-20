package de.titansmp.paper.clans

import de.titansmp.api.clans.*
import de.titansmp.api.event.*
import de.titansmp.api.player.NetworkPlayer
import de.titansmp.api.player.PlayerService
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Bukkit
import org.bukkit.command.*
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor

class ClansPaperModule(
    private val plugin: JavaPlugin,
    private val clans: ClanService,
    private val players: PlayerService,
    private val events: EventBus,
    private val permission: (Player, String) -> Boolean,
    private val pageSize: Int,
    private val executor: Executor,
    private val clanTeleportCommand: (Player, Array<out String>) -> Boolean = { _, _ -> false },
) : CommandExecutor, AutoCloseable {
    private val subscriptions = mutableListOf<EventSubscription>()

    fun register() {
        plugin.getCommand("clan")?.setExecutor(this) ?: error("Missing /clan command")
        ClanEventAction.entries.forEach { action -> subscriptions += events.subscribe(ClanChanged.TYPES.getValue(action), EventListener(::onClanEvent)) }
        subscriptions += events.subscribe(ClanChatMessage.TYPE, EventListener(::onClanChat))
    }
    override fun close() { subscriptions.forEach(EventSubscription::close); subscriptions.clear() }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = sender as? Player ?: run { sender.sendMessage(Component.text("Only players can use clans.")); return true }
        if (args.firstOrNull()?.lowercase() in setOf("home", "sethome", "delhome") && clanTeleportCommand(player,args)) return true
        if (label.equals("c", true)) { chat(player,args); return true }
        if (args.isEmpty()) { help(player); return true }
        when(args[0].lowercase()) {
            "create" -> if(args.size==3) clans.createClan(player.uniqueId,args[1],args[2]).whenComplete { clan,error -> main(player) { if(error!=null) fail(player,error) else player.sendMessage(Component.text("Clan ${clan.name} [${clan.tag}] created. You are an Admin.")) } } else usage(player,"/clan create <name> <tag>")
            "disband" -> if(args.size==1) clans.disbandClan(player.uniqueId).whenComplete { clan,error -> main(player) { if(error!=null) fail(player,error) else player.sendMessage(Component.text("Clan ${clan.name} was disbanded.")) } } else usage(player,"/clan disband")
            "promote","demote","kick" -> if(args.size==2) resolve(player,args[1]) { target ->
                when(args[0].lowercase()) {
                    "promote" -> clans.promote(player.uniqueId,target.uuid).whenComplete { role,error -> main(player) { if(error!=null) fail(player,error) else player.sendMessage(Component.text("${target.username} is now ${role.label}.")) } }
                    "demote" -> clans.demote(player.uniqueId,target.uuid).whenComplete { role,error -> main(player) { if(error!=null) fail(player,error) else player.sendMessage(Component.text("${target.username} is now ${role.label}.")) } }
                    else -> clans.kick(player.uniqueId,target.uuid).whenComplete { _,error -> main(player) { if(error!=null) fail(player,error) else player.sendMessage(Component.text("${target.username} was kicked from your clan.")) } }
                }
            } else usage(player,"/clan ${args[0]} <player>")
            "leave" -> if(args.size==1) clans.leave(player.uniqueId).whenComplete { _,error -> main(player) { if(error!=null) fail(player,error) else player.sendMessage(Component.text("You left your clan.")) } } else usage(player,"/clan leave")
            "invite" -> if(args.size==2) resolve(player,args[1]) { target -> clans.invite(player.uniqueId,target.uuid).whenComplete { invite,error -> main(player) { if(error!=null) fail(player,error) else player.sendMessage(Component.text("${target.username} was invited to ${invite.clanName}.")) } } } else usage(player,"/clan invite <player>")
            "accept","reject" -> if(args.size==2) clans.getClanByName(args[1]).whenComplete { clan,error ->
                if(error!=null) main(player) { fail(player,error) }
                else if(clan==null) main(player) { player.sendMessage(Component.text("Clan '${args[1]}' was not found.")) }
                else invitationAction(player,clan.uuid,args[0].equals("accept",true))
            } else usage(player,"/clan ${args[0]} <clanname>")
            "__accept","__reject" -> if(args.size==2) runCatching { UUID.fromString(args[1]) }.onSuccess { invitationAction(player,it,args[0]=="__accept") }.onFailure { usage(player,"/clan invitations") } else usage(player,"/clan invitations")
            "invitations" -> invitations(player,args)
            "chat" -> chat(player,args.drop(1).toTypedArray())
            "info" -> if(args.size==1) info(player) else usage(player,"/clan info")
            "color" -> color(player,args)
            else -> { help(player); return true }
        }
        return true
    }

    private fun invitationAction(player:Player,clanId:UUID,accept:Boolean) {
        if(accept) clans.acceptInvitation(player.uniqueId,clanId).whenComplete { clan,error -> main(player) { if(error!=null) fail(player,error) else player.sendMessage(Component.text("You joined ${clan.name} [${clan.tag}].")) } }
        else clans.rejectInvitation(player.uniqueId,clanId).whenComplete { _,error -> main(player) { if(error!=null) fail(player,error) else player.sendMessage(Component.text("Clan invitation rejected.")) } }
    }

    private fun invitations(player:Player,args:Array<out String>) {
        if(args.size>2){usage(player,"/clan invitations [page]");return}
        val page=args.getOrNull(1)?.toIntOrNull()?.takeIf { it>0 } ?: if(args.size==1) 1 else {usage(player,"/clan invitations [page]");return}
        clans.listInvitations(player.uniqueId,page,pageSize).whenComplete { result,error -> if(error!=null) main(player) { fail(player,error) } else executor.execute {
            val rows=result.entries.map { invite ->
                val inviter=players.findPlayer(invite.inviterUuid)?.username ?: "Unknown"
                Component.text("${invite.clanName} [${invite.clanTag}] — invited by $inviter ")
                    .append(button("[ ACCEPT ]",NamedTextColor.GREEN,"/clan __accept ${invite.clanUuid}"))
                    .append(Component.text(" "))
                    .append(button("[ REJECT ]",NamedTextColor.RED,"/clan __reject ${invite.clanUuid}"))
            }
            main(player) { player.sendMessage(Component.text("Clan Invitations (${result.page}/${result.pageCount})",NamedTextColor.GOLD)); rows.forEach(player::sendMessage) }
        } }
    }

    private fun info(player:Player) {
        clans.getClanByMember(player.uniqueId).whenComplete { clan,error ->
            if(error!=null) return@whenComplete main(player){fail(player,error)}
            if(clan==null) return@whenComplete main(player){player.sendMessage(Component.text("You are not in a clan."))}
            clans.listMembers(clan.uuid,1,100).whenComplete { page,failure -> if(failure!=null) main(player){fail(player,failure)} else executor.execute {
            val members=page.entries.map { m ->
                    val identity=players.findPlayer(m.playerUuid)
                    val name=identity?.username ?: m.playerUuid.toString()
                    val online=identity?.online==true
                    Component.text("${m.role.label}  $name — ${if(online) "Online" else "Offline"}",if(online) NamedTextColor.GREEN else NamedTextColor.GRAY)
                }
            val onlineCount=page.entries.count { players.findPlayer(it.playerUuid)?.online==true }
                main(player) {
                    player.sendMessage(Component.text("${clan.name} [${clan.tag}]",NamedTextColor.GOLD))
                player.sendMessage(Component.text("Color: ${clan.color.mode.name.lowercase()} ${clan.color.color}; Members: ${page.total}; Online: $onlineCount"))
                    members.forEach(player::sendMessage)
                    if(page.total>page.entries.size) player.sendMessage(Component.text("Showing ${page.entries.size} of ${page.total} members."))
                }
            } }
        }
    }

    private fun color(player:Player,args:Array<out String>) {
        if(args.size !in 2..3){usage(player,"/clan color <color> | /clan color rainbow <color>");return}
        val rainbow=args.getOrNull(1)?.equals("rainbow",true)==true
        if((rainbow&&args.size!=3)||(!rainbow&&args.size!=2)){usage(player,"/clan color <color> | /clan color rainbow <color>");return}
        val value=if(rainbow) args.getOrNull(2) else args.getOrNull(1)
        if(value==null){usage(player,"/clan color rainbow <color>");return}
        if(!permission(player,"titansmp.clan.color")){player.sendMessage(Component.text("You do not have permission to change clan colors."));return}
        if(rainbow&&!permission(player,"titansmp.clan.color.rainbow")){player.sendMessage(Component.text("You do not have permission to use rainbow clan colors."));return}
        val mode=if(rainbow) ClanColorMode.RAINBOW else ClanColorMode.STANDARD
        clans.setColor(player.uniqueId,ClanColor(mode,value.lowercase())).whenComplete { _,error -> main(player) { if(error!=null) fail(player,error) else player.sendMessage(Component.text("Clan color set to ${mode.name.lowercase()} $value.")) } }
    }

    private fun chat(player:Player,args:Array<out String>) {
        val message=args.joinToString(" ").trim()
        if(message.isBlank()){usage(player,"/clan chat <message>");return}
        clans.sendClanChat(player.uniqueId,player.name,message).whenComplete { sent,error -> main(player) { if(error!=null) fail(player,error) else if(sent!=true) player.sendMessage(Component.text("You must be in a clan to use clan chat.")) } }
    }

    private fun help(player:Player) {
        clans.getClanByMember(player.uniqueId).whenComplete { clan,_ ->
            if(clan==null) return@whenComplete main(player){listOf("/clan create <name> <tag>","/clan accept <clanname>","/clan reject <clanname>","/clan invitations").forEach { player.sendMessage(Component.text(it,NamedTextColor.YELLOW)) }}
            clans.listMembers(clan.uuid,1,100).whenComplete { page,_ ->
                val role=page?.entries?.firstOrNull { it.playerUuid==player.uniqueId }?.role
                main(player) {
                    val entries=mutableListOf("/clan reject <clanname>","/clan invitations","/clan leave","/clan chat <message>","/c <message>","/clan info")
                    if(role==ClanRole.MODERATOR||role==ClanRole.ADMIN) entries+=listOf("/clan invite <player>","/clan kick <player>")
                    if(role==ClanRole.ADMIN){
                        entries+=listOf("/clan promote <player>","/clan demote <player>","/clan disband")
                        if(permission(player,"titansmp.clan.color")) entries+="/clan color <color>"
                        if(permission(player,"titansmp.clan.color.rainbow")) entries+="/clan color rainbow <color>"
                    }
                    entries.forEach { player.sendMessage(Component.text(it,NamedTextColor.YELLOW)) }
                }
            }
        }
    }

    private fun resolve(player:Player,name:String,action:(NetworkPlayer)->Unit) { executor.execute {
        val target=runCatching { players.findPlayerByName(name) }.getOrNull()
        Bukkit.getScheduler().runTask(plugin,Runnable { if(target==null) player.sendMessage(Component.text("Player '$name' was not found.")) else action(target) })
    } }

    private fun onClanEvent(envelope:EventEnvelope) {
        val e=envelope.event as? ClanChanged ?: return
        when(e.action) {
            ClanEventAction.INVITATION_CREATED -> e.targetUuid?.let { target -> executor.execute {
                val inviter=players.findPlayer(e.actorUuid)?.username ?: "A player"
                Bukkit.getScheduler().runTask(plugin,Runnable { Bukkit.getPlayer(target)?.let { p ->
                    p.sendMessage(Component.text("$inviter invited you to join clan ${e.clanName} [${e.clanTag}]."))
                    p.sendMessage(button("[ ACCEPT ]",NamedTextColor.GREEN,"/clan __accept ${e.clanUuid}").append(Component.text(" ")).append(button("[ REJECT ]",NamedTextColor.RED,"/clan __reject ${e.clanUuid}")))
                } })
            } }
            ClanEventAction.INVITATION_ACCEPTED -> e.targetUuid?.let { joined -> withName(joined) { name -> notifyMembers(e.clanUuid,joined,"$name joined the clan.") } }
            ClanEventAction.MEMBER_LEFT -> e.targetUuid?.let { left -> withName(left) { name -> notifyMembers(e.clanUuid,left,"$name left the clan.") } }
            ClanEventAction.MEMBER_KICKED -> e.targetUuid?.let { target ->
                Bukkit.getScheduler().runTask(plugin,Runnable { Bukkit.getPlayer(target)?.sendMessage(Component.text("You were kicked from clan ${e.clanName}.")) })
                withName(target) { name -> notifyMembers(e.clanUuid,target,"$name was kicked from the clan.") }
            }
            ClanEventAction.MEMBER_ROLE_CHANGED -> e.targetUuid?.let { target -> e.role?.let { role -> Bukkit.getScheduler().runTask(plugin,Runnable { Bukkit.getPlayer(target)?.sendMessage(Component.text("Your clan role is now ${role.label}.")) }) } }
            ClanEventAction.INVITATION_REJECTED -> e.targetUuid?.let { inviter -> withName(e.actorUuid) { name -> Bukkit.getScheduler().runTask(plugin,Runnable { Bukkit.getPlayer(inviter)?.sendMessage(Component.text("$name rejected your clan invitation.")) }) } }
            ClanEventAction.DISBANDED -> e.affectedMembers.forEach { id -> Bukkit.getScheduler().runTask(plugin,Runnable { Bukkit.getPlayer(id)?.sendMessage(Component.text("Clan ${e.clanName} was disbanded.")) }) }
            else -> Unit
        }
    }

    private fun notifyMembers(clanId:UUID,exclude:UUID,message:String) {
        notifyMemberPage(clanId,exclude,message,1)
    }
    private fun notifyMemberPage(clanId:UUID,exclude:UUID,message:String,page:Int) {
        clans.listMembers(clanId,page,100).whenComplete { result,failure ->
            if(failure!=null)return@whenComplete
            result.entries.filter { it.playerUuid!=exclude }.forEach { member -> Bukkit.getScheduler().runTask(plugin,Runnable { Bukkit.getPlayer(member.playerUuid)?.sendMessage(Component.text(message)) }) }
            if(page<result.pageCount)notifyMemberPage(clanId,exclude,message,page+1)
        }
    }

    private fun onClanChat(envelope:EventEnvelope) {
        val e=envelope.event as? ClanChatMessage ?: return
        Bukkit.getScheduler().runTask(plugin,Runnable {
            Bukkit.getOnlinePlayers().map { it.uniqueId }.forEach { id ->
                clans.getClanByMember(id).whenComplete { clan,failure ->
                    if(failure==null&&clan?.uuid==e.clanUuid) Bukkit.getScheduler().runTask(plugin,Runnable { Bukkit.getPlayer(id)?.sendMessage(renderChat(e)) })
                }
            }
        })
    }
    private fun renderChat(e:ClanChatMessage):Component {
        val tag=Component.text("[Clan] ").append(Component.text("[${e.clanTag}] ",textColor(e.colorValue)))
        val sender=Component.text("${e.senderName}: ",NamedTextColor.WHITE)
        val body=if(e.colorMode==ClanColorMode.RAINBOW) rainbow(e.message,e.colorValue) else Component.text(e.message,textColor(e.colorValue))
        return tag.append(sender).append(body)
    }
    private fun rainbow(message:String,base:String):Component {
        val palette=listOf(NamedTextColor.RED,NamedTextColor.GOLD,NamedTextColor.YELLOW,NamedTextColor.GREEN,NamedTextColor.AQUA,NamedTextColor.BLUE,NamedTextColor.LIGHT_PURPLE)
        val start=palette.indexOf(textColor(base)).coerceAtLeast(0)
        return message.mapIndexed { index,char -> Component.text(char.toString(),palette[(index+start)%palette.size]) }.fold(Component.empty()) { all,part -> all.append(part) }
    }
    private fun textColor(name:String):TextColor=when(name.lowercase()) {
        "black"->NamedTextColor.BLACK;"dark_blue"->NamedTextColor.DARK_BLUE;"dark_green"->NamedTextColor.DARK_GREEN;"dark_aqua"->NamedTextColor.DARK_AQUA;"dark_red"->NamedTextColor.DARK_RED;"dark_purple"->NamedTextColor.DARK_PURPLE;"gold"->NamedTextColor.GOLD;"gray"->NamedTextColor.GRAY;"dark_gray"->NamedTextColor.DARK_GRAY;"blue"->NamedTextColor.BLUE;"green"->NamedTextColor.GREEN;"aqua"->NamedTextColor.AQUA;"red"->NamedTextColor.RED;"light_purple"->NamedTextColor.LIGHT_PURPLE;"yellow"->NamedTextColor.YELLOW;else->NamedTextColor.WHITE
    }
    private fun button(label:String,color:NamedTextColor,cmd:String)=Component.text(label,color).clickEvent(ClickEvent.runCommand(cmd)).hoverEvent(HoverEvent.showText(Component.text("Click to respond")))
    private fun withName(id:UUID,action:(String)->Unit){executor.execute { action(players.findPlayer(id)?.username?:"A player") }}
    private fun main(player:Player,action:()->Unit){Bukkit.getScheduler().runTask(plugin,Runnable{if(player.isOnline)action()})}
    private fun usage(player:Player,text:String){player.sendMessage(Component.text("Usage: $text",NamedTextColor.YELLOW))}
    private fun fail(player:Player,failure:Throwable){
        val e=if(failure is CompletionException&&failure.cause!=null)failure.cause!! else failure
        val clanError=e as? ClanOperationException
        val msg=when(clanError?.reason){
            ClanFailure.ALREADY_IN_CLAN->"You are already in a clan.";ClanFailure.NAME_TAKEN->"That clan name is already in use.";ClanFailure.TAG_TAKEN->"That clan tag is already in use."
            ClanFailure.INVALID_NAME->"Clan names must be 3–24 characters using letters, numbers, spaces, '_' or '-'.";ClanFailure.INVALID_TAG->"Clan tags must be 2–8 letters or numbers.";ClanFailure.INVALID_COLOR->"That clan color is not supported."
            ClanFailure.CLAN_NOT_FOUND->"That clan no longer exists.";ClanFailure.NOT_IN_CLAN->"You are not in a clan.";ClanFailure.NOT_ADMIN->"Only a clan Admin can do that.";ClanFailure.NOT_MODERATOR->"A clan Moderator or Admin is required."
            ClanFailure.TARGET_NOT_MEMBER->"That player is not in your clan.";ClanFailure.TARGET_IN_CLAN->"That player is already in a clan.";ClanFailure.SELF_TARGET->"You cannot target yourself.";ClanFailure.ALREADY_ADMIN->"That player is already an Admin."
            ClanFailure.LAST_ADMIN->clanError.message?:"At least one Admin must remain. Promote another member to Admin first.";ClanFailure.CANNOT_KICK_ADMIN->"Moderators cannot kick an Admin.";ClanFailure.INVITATION_EXISTS->"That player already has a pending invitation."
            ClanFailure.INVITATION_NOT_FOUND->"No invitation from that clan was found.";ClanFailure.INVITATION_EXPIRED->"That clan invitation has expired.";ClanFailure.TARGET_BLOCKED->"Clan invitations are unavailable for that player."
            null->e.message?:"unknown error"
        }
        player.sendMessage(Component.text(msg,NamedTextColor.RED))
    }
    private val ClanRole.label:String get()=when(this){ClanRole.ADMIN->"Admin";ClanRole.MODERATOR->"Moderator";ClanRole.MEMBER->"Member"}
}
