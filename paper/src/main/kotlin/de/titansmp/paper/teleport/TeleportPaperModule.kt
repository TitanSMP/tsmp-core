package de.titansmp.paper.teleport

import de.titansmp.api.clans.ClanService
import de.titansmp.api.event.*
import de.titansmp.api.teleport.*
import de.titansmp.api.player.PlayerService
import de.titansmp.core.config.TeleportConfig
import de.titansmp.core.teleport.TeleportPermissionResolver
import de.titansmp.core.redis.RedisService
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.command.*
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.java.JavaPlugin
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.logging.Level

class TeleportPaperModule(
    private val plugin: JavaPlugin,
    private val service: TeleportService,
    private val clans: ClanService?,
    private val players: PlayerService,
    private val events: EventBus,
    private val redis: RedisService,
    private val config: TeleportConfig,
    private val serverId: String,
    private val serverType: String,
    private val executor: Executor,
    private val permissionNodesFor: (UUID) -> CompletionStage<Set<String>>,
) : Listener, AutoCloseable {
    private data class PendingTeleport(val operationId:UUID,val ownerUuid:UUID,val category:TeleportCooldownCategory,val scope:String?,val destination:StoredTeleportLocation)
    private val subscriptions = mutableListOf<EventSubscription>()

    fun register() {
        registerCommand("tpa", ::tpa); registerCommand("tpahere", ::tpahere)
        registerCommand("tpaccept", ::tpaccept); registerCommand("home", ::home)
        registerCommand("tpdeny", ::tpdeny)
        registerCommand("sethome", ::sethome); registerCommand("delhome", ::delhome)
        registerCommand("rtp", ::rtp)
        Bukkit.getPluginManager().registerEvents(this,plugin)
        subscriptions += events.subscribe(TeleportRequestCreated.TYPE,EventListener { envelope ->
            val event=envelope.event as? TeleportRequestCreated ?: return@EventListener
            Bukkit.getScheduler().runTask(plugin,Runnable {
                Bukkit.getPlayer(event.targetUuid)?.sendMessage(
                    Component.text(if(event.direction==TeleportDirection.REQUESTER_TO_TARGET) "${event.requesterName} wants to teleport to you. " else "${event.requesterName} wants you to teleport to them. ")
                        .append(Component.text("[ ACCEPT ]",NamedTextColor.GREEN).clickEvent(ClickEvent.runCommand("/tpaccept")))
                        .append(Component.text(" "))
                        .append(Component.text("[ REJECT ]",NamedTextColor.RED).clickEvent(ClickEvent.runCommand("/tpdeny"))),
                )
            })
        })
        subscriptions += events.subscribe(TeleportRequestResolved.TYPE,EventListener { envelope ->
            val event=envelope.event as? TeleportRequestResolved ?: return@EventListener
            Bukkit.getScheduler().runTask(plugin,Runnable {
                Bukkit.getPlayer(event.requesterUuid)?.sendMessage(Component.text(if(event.accepted) "Your teleport request was accepted." else "Your teleport request was rejected."))
            })
        })
        subscriptions += events.subscribe(TeleportLocationRequested.TYPE,EventListener { envelope ->
            val event=envelope.event as? TeleportLocationRequested ?: return@EventListener
            executor.execute {
                val presence=players.findPlayer(event.requesterUuid)
                if(presence?.online==true && presence.currentServer==serverId) Bukkit.getScheduler().runTask(plugin,Runnable {
                    Bukkit.getPlayer(event.requesterUuid)?.let { requester ->
                        transferRemote(event.movingPlayerUuid,requester.location.snapshot(),event.operationId,event.requesterUuid,TeleportCooldownCategory.TPAHERE,null)
                    }
                })
            }
        })
        subscriptions += events.subscribe(TeleportTransferRequested.TYPE,EventListener { envelope ->
            val event=envelope.event as? TeleportTransferRequested ?: return@EventListener
            if(event.targetServerId==serverId || event.targetServerType==serverType) applyPending(event.playerUuid)
        })
    }
    private fun registerCommand(name:String, handler:(Player,Array<out String>)->Unit) {
        plugin.getCommand(name)?.setExecutor { sender,_,_,args ->
            val p=sender as? Player ?: run { sender.sendMessage("Only players can use this command."); return@setExecutor true }
            handler(p,args); true
        } ?: error("Missing /$name command")
    }

    fun handleClanCommand(player:Player,args:Array<out String>):Boolean {
        val clanService=clans ?: return false
        return when(args.firstOrNull()?.lowercase()) {
            "home" -> { if(args.size>2) { usage(player,"/clan home [name]");return true }; clanService.getClanByMember(player.uniqueId).whenComplete { clan,e -> main(player) { if(e!=null) fail(player,e) else if(clan==null) player.sendMessage(Component.text("You are not in a clan.")) else service.getClanHome(clan.uuid,args.getOrNull(1)).whenComplete { home,error -> main(player) { if(error!=null) fail(player,error) else if(home==null) player.sendMessage(Component.text("No clan home found.")) else teleport(player,home.destination,TeleportCooldownCategory.CLAN_HOME,home.normalizedName) } } } };true }
            "sethome" -> { if(args.size!=2) { usage(player,"/clan sethome <name>");return true }; val location=player.location.snapshot();clanService.getClanByMember(player.uniqueId).whenComplete { clan,e -> main(player) { if(e!=null) fail(player,e) else if(clan==null) player.sendMessage(Component.text("You are not in a clan.")) else clanAdminsLimit(clan.uuid,{ limit -> saveClanHome(player,clan.uuid,args[1],limit,location) },{ player.sendMessage(Component.text("Clan home permissions could not be resolved.")) }) } };true }
            "delhome" -> { if(args.size!=2) { usage(player,"/clan delhome <name>");return true }; clanService.getClanByMember(player.uniqueId).whenComplete { clan,e -> main(player) { if(e!=null) fail(player,e) else if(clan==null) player.sendMessage(Component.text("You are not in a clan.")) else service.deleteClanHome(player.uniqueId,clan.uuid,args[1]).whenComplete { ok,error -> main(player) { if(error!=null) fail(player,error) else player.sendMessage(Component.text(if(ok) "Clan home deleted." else "Clan home not found.")) } } } };true }
            else -> false
        }
    }

    private fun sethome(p:Player,args:Array<out String>) {
        if(args.size!=1) { usage(p,"/sethome <name>");return }
        val destination=p.location.snapshot()
        val limit=TeleportPermissionResolver.homeLimit(p.effectivePermissions.filter { it.value }.map { it.permission },config.defaultHomeLimit)
        service.setHome(p.uniqueId,args[0],limit,destination).whenComplete { home,e -> main(p) { if(e!=null) fail(p,e) else p.sendMessage(Component.text("Home '${home.displayName}' saved.")) } }
    }
    private fun delhome(p:Player,args:Array<out String>) {
        if(args.size!=1) { usage(p,"/delhome <name>");return }
        service.deleteHome(p.uniqueId,args[0]).whenComplete { ok,e -> main(p) { if(e!=null) fail(p,e) else p.sendMessage(Component.text(if(ok) "Home deleted." else "Home not found.")) } }
    }
    private fun home(p:Player,args:Array<out String>) {
        if(args.size!=1) { usage(p,"/home <name>");return }
        service.getHome(p.uniqueId,args[0]).whenComplete { home,e -> main(p) { if(e!=null) fail(p,e) else if(home==null) p.sendMessage(Component.text("Home not found.")) else teleport(p,home.destination,TeleportCooldownCategory.HOME,home.normalizedName) } }
    }
    private fun teleport(p:Player,destination:StoredTeleportLocation,category:TeleportCooldownCategory,scope:String?) {
        val permissions=p.effectivePermissions.filter { it.value }.map { it.permission }
        val cooldown=TeleportPermissionResolver.cooldownMinutes(permissions,config.defaultCooldownMinutes)
        if(destination.serverId==serverId) {
            val world=Bukkit.getWorld(destination.location.world)
            if(world==null) { plugin.logger.warning("Stored teleport world '${destination.location.world}' is unavailable for ${p.uniqueId}");p.sendMessage(Component.text("That destination world is unavailable."));return }
            service.reserveCooldown(p.uniqueId,category,scope,cooldown).whenComplete { status,e -> main(p) {
                if(e!=null) fail(p,e)
                else if(!status.available) p.sendMessage(Component.text("Teleport is cooling down for ${status.remainingSeconds} seconds."))
                else if(!p.teleport(destination.location.toBukkit(world))) { service.releaseCooldown(p.uniqueId,category,scope);p.sendMessage(Component.text("Teleport failed.")) }
            } }
        } else {
            service.reserveCooldown(p.uniqueId,category,scope,cooldown).whenComplete { status,e ->
                if(e!=null) main(p){fail(p,e)} else if(!status.available) main(p){p.sendMessage(Component.text("Teleport is cooling down for ${status.remainingSeconds} seconds."))} else transferRemote(p.uniqueId,destination,UUID.randomUUID(),p.uniqueId,category,scope)
            }
        }
    }
    private fun tpa(p:Player,args:Array<out String>)=request(p,args,TeleportDirection.REQUESTER_TO_TARGET)
    private fun tpahere(p:Player,args:Array<out String>)=request(p,args,TeleportDirection.TARGET_TO_REQUESTER)
    private fun request(p:Player,args:Array<out String>,direction:TeleportDirection) {
        if(args.size!=1) { usage(p,if(direction==TeleportDirection.REQUESTER_TO_TARGET) "/tpa <player>" else "/tpahere <player>");return }
        val permissionNodes=p.effectivePermissions.filter { it.value }.map { it.permission }
        executor.execute {
            val target=players.findPlayerByName(args[0])
            if(target==null || !target.online) { main(p){p.sendMessage(Component.text("That player is not online."))};return@execute }
        val category=if(direction==TeleportDirection.REQUESTER_TO_TARGET) TeleportCooldownCategory.TPA else TeleportCooldownCategory.TPAHERE
        val cooldown=TeleportPermissionResolver.cooldownMinutes(permissionNodes,config.defaultCooldownMinutes)
        service.reserveCooldown(p.uniqueId,category,null,cooldown).whenComplete { status,cooldownError ->
            if(cooldownError!=null) { main(p){fail(p,cooldownError)};return@whenComplete }
            if(!status.available) { main(p){p.sendMessage(Component.text("Teleport request is cooling down for ${status.remainingSeconds} seconds."))};return@whenComplete }
            service.requestTeleport(p.uniqueId,target.uuid,p.name,direction).whenComplete { req,e ->
                if(e!=null) { service.releaseCooldown(p.uniqueId,category,null);main(p){fail(p,e)} }
                else main(p) { p.sendMessage(Component.text("Teleport request sent."));executor.execute { events.publish(TeleportRequestCreated(EventMetadata(eventType=TeleportRequestCreated.TYPE,sourceServerId=serverId),req.requestId,req.requesterUuid,req.targetUuid,req.requesterName,req.direction,req.expiresAt)) } }
            }
        }
        }
    }
    private fun tpaccept(p:Player,args:Array<out String>) {
        if(args.isNotEmpty()) { usage(p,"/tpaccept");return }
        service.acceptTeleportRequest(p.uniqueId).whenComplete { request,e -> main(p) {
            if(e!=null) fail(p,e) else executor.execute {
                val requester=players.findPlayer(request.requesterUuid)
                if(requester==null || !requester.online) { main(p){p.sendMessage(Component.text("That request is no longer valid; requester is offline."))};return@execute }
                if(request.direction==TeleportDirection.REQUESTER_TO_TARGET) {
                    val destination=p.location.snapshot()
                    if(requester.currentServer==serverId) Bukkit.getPlayer(request.requesterUuid)?.let { mover -> main(mover){ if(!completeLocalTeleport(mover,destination)) service.releaseCooldown(request.requesterUuid,TeleportCooldownCategory.TPA,null) } }
                    else transferRemote(request.requesterUuid,destination,request.requestId,request.requesterUuid,TeleportCooldownCategory.TPA,null)
                } else {
                    events.publish(TeleportLocationRequested(EventMetadata(eventType=TeleportLocationRequested.TYPE,sourceServerId=serverId),request.requestId,request.requesterUuid,request.targetUuid))
                }
                events.publish(TeleportRequestResolved(EventMetadata(eventType=TeleportRequestResolved.TYPE,sourceServerId=serverId),request.requestId,request.requesterUuid,request.targetUuid,true))
            }
        } }
    }
    private fun tpdeny(p:Player,args:Array<out String>) {
        if(args.isNotEmpty()) { usage(p,"/tpdeny");return }
        service.rejectTeleportRequest(p.uniqueId).whenComplete { request,error ->
            if(error!=null) main(p){fail(p,error)} else {
                main(p){p.sendMessage(Component.text("Teleport request rejected."))}
                executor.execute { events.publish(TeleportRequestResolved(EventMetadata(eventType=TeleportRequestResolved.TYPE,sourceServerId=serverId),request.requestId,request.requesterUuid,request.targetUuid,false)) }
            }
        }
    }
    private fun rtp(p:Player,args:Array<out String>) {
        if(args.isNotEmpty()) { usage(p,"/rtp");return }
        if(config.rtpServerTypes.isEmpty()) { p.sendMessage(Component.text("Random teleport has no eligible server types configured."));return }
        val permissions=p.effectivePermissions.filter { it.value }.map { it.permission };val cooldown=TeleportPermissionResolver.cooldownMinutes(permissions,config.defaultCooldownMinutes)
        if(serverType.lowercase() !in config.rtpServerTypes) {
            val selectedType=config.rtpServerTypes.sorted().first()
            service.reserveCooldown(p.uniqueId,TeleportCooldownCategory.RTP,null,cooldown).whenComplete { status,error ->
                if(error!=null) main(p){fail(p,error)}
                else if(!status.available) main(p){p.sendMessage(Component.text("Teleport is cooling down for ${status.remainingSeconds} seconds."))}
                else transferRemote(p.uniqueId,StoredTeleportLocation("type:$selectedType",TeleportLocation("__RTP__",0.0,0.0,0.0,0f,0f)),UUID.randomUUID(),p.uniqueId,TeleportCooldownCategory.RTP,null)
            }
            return
        }
        val result=findSafeLocation(p)
        if(result==null) { p.sendMessage(Component.text("No safe random destination was found."));return }
        service.reserveCooldown(p.uniqueId,TeleportCooldownCategory.RTP,null,cooldown).whenComplete { status,e -> main(p) { if(e!=null) fail(p,e) else if(!status.available) p.sendMessage(Component.text("Teleport is cooling down for ${status.remainingSeconds} seconds.")) else if(!completeLocalTeleport(p,result)) service.releaseCooldown(p.uniqueId,TeleportCooldownCategory.RTP,null) } }
    }
    private fun completeLocalTeleport(p:Player,destination:StoredTeleportLocation):Boolean {
        val world=Bukkit.getWorld(destination.location.world) ?: run { p.sendMessage(Component.text("That destination world is unavailable."));return false }
        if(!p.teleport(destination.location.toBukkit(world))) { p.sendMessage(Component.text("Teleport failed."));return false }
        return true
    }
    private fun transferRemote(playerUuid:UUID,destination:StoredTeleportLocation,operationId:UUID,cooldownOwner:UUID,category:TeleportCooldownCategory,scope:String?) {
        executor.execute {
            val key="titansmp:teleport:pending:$playerUuid"
            redis.commands.set(key,encode(destination,operationId,cooldownOwner,category,scope),io.lettuce.core.SetArgs.Builder.ex(if(destination.serverId.startsWith("type:")) 86_400 else 300))
            val isTypeTarget=destination.serverId.startsWith("type:")
            events.publish(TeleportTransferRequested(EventMetadata(eventType=TeleportTransferRequested.TYPE,sourceServerId=serverId),operationId,playerUuid,destination.serverId.takeUnless { isTypeTarget },cooldownOwner,category,scope,destination.serverId.removePrefix("type:").takeIf { isTypeTarget }))
                .whenComplete { _,failure -> if(failure!=null) { redis.commands.del(key);redis.commands.del(cooldownKey(cooldownOwner,category,scope)) } }
        }
    }
    private fun findSafeLocation(p:Player):StoredTeleportLocation? {
        val world=p.world
        val border=world.worldBorder;val center=border.center
        val loadedChunks=world.loadedChunks
        if(loadedChunks.isEmpty()) return null
        repeat(config.rtpMaxAttempts) {
            val chunk=loadedChunks.random()
            val x=chunk.x*16+(0..15).random();val z=chunk.z*16+(0..15).random()
            if(!border.isInside(Location(world,x.toDouble(),world.minHeight.toDouble(),z.toDouble()))) return@repeat
            if(!world.isChunkLoaded(x shr 4,z shr 4)) return@repeat
            val y=world.getHighestBlockYAt(x,z);if(y<=world.minHeight+1 || y>=world.maxHeight-2) return@repeat
            val ground=world.getBlockAt(x,y-1,z);val feet=world.getBlockAt(x,y,z);val head=world.getBlockAt(x,y+1,z)
            if(ground.type.isSolid && ground.type!=Material.LAVA && feet.isPassable && head.isPassable && feet.type!=Material.LAVA && head.type!=Material.LAVA)
                return StoredTeleportLocation(serverId,TeleportLocation(world.name,x+0.5,y.toDouble(),z+0.5,p.location.yaw,p.location.pitch))
        }
        return null
    }
    private fun saveClanHome(p:Player,clan:UUID,homeName:String,limit:Int,location:StoredTeleportLocation) {
        service.setClanHome(p.uniqueId,clan,homeName,limit,location).whenComplete { home,e -> main(p) { if(e!=null) fail(p,e) else p.sendMessage(Component.text("Clan home '${home.displayName}' saved.")) } }
    }
    private fun clanAdminsLimit(clan:UUID,done:(Int)->Unit,onError:()->Unit) {
        val clanService=clans ?: run { onError();return }
        clanService.listMembers(clan,1,100).thenCompose { first ->
            val additional=(2..first.pageCount).map { page -> clanService.listMembers(clan,page,100).toCompletableFuture() }
            CompletableFuture.allOf(*additional.toTypedArray()).thenApply { first.entries + additional.flatMap { it.join().entries } }
        }.thenCompose { members ->
            val adminPermissions=members.filter { it.role.name=="ADMIN" }.map { permissionNodesFor(it.playerUuid).toCompletableFuture() }
            CompletableFuture.allOf(*adminPermissions.toTypedArray()).thenApply { TeleportPermissionResolver.clanHomeLimit(adminPermissions.map { it.join() },config.defaultClanHomeLimit) }
        }.whenComplete { limit,error ->
            Bukkit.getScheduler().runTask(plugin,Runnable {
                if(error!=null) { plugin.logger.warning("Could not resolve clan home capacity: ${error.message}");onError() } else done(limit)
            })
        }
    }
    @EventHandler fun onJoin(event:PlayerJoinEvent) {
        applyPending(event.player.uniqueId)
    }
    private fun applyPending(playerUuid:UUID) {
        executor.execute {
            val key="titansmp:teleport:pending:$playerUuid"
            val raw=redis.commands.get(key) ?: return@execute
            try { val pending=decode(raw);val stored=pending.destination;if(stored.location.world=="__RTP__" || stored.serverId==serverId) { Bukkit.getScheduler().runTask(plugin,Runnable { val p=Bukkit.getPlayer(playerUuid);if(p!=null && p.isOnline) {
                val dest=if(stored.location.world=="__RTP__") { if(serverType !in config.rtpServerTypes) null else findSafeLocation(p) } else stored
                if(dest==null) { service.releaseCooldown(pending.ownerUuid,pending.category,pending.scope);p.sendMessage(Component.text("No safe random destination was found."));executor.execute { redis.commands.del(key) } }
                else {
                val world=Bukkit.getWorld(dest.location.world);if(world==null) { plugin.logger.warning("Stored teleport world '${dest.location.world}' is unavailable for $playerUuid");p.sendMessage(Component.text("That destination world is unavailable."));service.releaseCooldown(pending.ownerUuid,pending.category,pending.scope);executor.execute { redis.commands.del(key) } } else executor.execute {
                val appliedKey="titansmp:teleport:applied:$playerUuid:${pending.operationId}"
                if(redis.commands.set(appliedKey,"1",io.lettuce.core.SetArgs.Builder.nx().ex(600))=="OK") {
                    Bukkit.getScheduler().runTask(plugin,Runnable {
                        if(p.isOnline && p.teleport(dest.location.toBukkit(world))) executor.execute { service.completeOperation(TeleportOperation(pending.operationId,playerUuid,dest)).whenComplete { _,_ -> redis.commands.del(key) } }
                        else executor.execute { service.releaseCooldown(pending.ownerUuid,pending.category,pending.scope);redis.commands.del(appliedKey) }
                    })
                }
            } }
            } }) } }
            catch(failure:Exception) { plugin.logger.log(Level.WARNING,"Invalid pending teleport payload for $playerUuid",failure);redis.commands.del(key) }
        }
    }
    private fun encode(d:StoredTeleportLocation,operationId:UUID,owner:UUID,category:TeleportCooldownCategory,scope:String?)=listOf(operationId,owner,category.name,scope?.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(StandardCharsets.UTF_8)) } ?: "-",d.serverId,Base64.getUrlEncoder().withoutPadding().encodeToString(d.location.world.toByteArray(StandardCharsets.UTF_8)),d.location.x,d.location.y,d.location.z,d.location.yaw,d.location.pitch).joinToString("|")
    private fun decode(raw:String):PendingTeleport { val a=raw.split('|');require(a.size==11);val scope=a[3].takeUnless { it=="-" }?.let { String(Base64.getUrlDecoder().decode(it),StandardCharsets.UTF_8) };return PendingTeleport(UUID.fromString(a[0]),UUID.fromString(a[1]),TeleportCooldownCategory.valueOf(a[2]),scope,StoredTeleportLocation(a[4],TeleportLocation(String(Base64.getUrlDecoder().decode(a[5]),StandardCharsets.UTF_8),a[6].toDouble(),a[7].toDouble(),a[8].toDouble(),a[9].toFloat(),a[10].toFloat()))) }
    private fun cooldownKey(uuid:UUID,category:TeleportCooldownCategory,scope:String?)="titansmp:teleport:cooldown:$uuid:${category.name}:${scope?.lowercase() ?: "-"}"
    private fun Location.snapshot()=StoredTeleportLocation(serverId,TeleportLocation(world.name,x,y,z,yaw,pitch))
    private fun TeleportLocation.toBukkit(world:org.bukkit.World)=Location(world,x,y,z,yaw,pitch)
    private fun main(player:Player,block:()->Unit) { if(!player.isOnline) return;Bukkit.getScheduler().runTask(plugin,Runnable(block)) }
    private fun usage(p:Player,text:String) { p.sendMessage(Component.text("Usage: $text")) }
    private fun fail(p:Player,e:Throwable) { val cause=generateSequence(e){it.cause}.last();p.sendMessage(Component.text((cause as? TeleportOperationException)?.message ?: "Teleport operation failed.")) }
    override fun close() { subscriptions.forEach(EventSubscription::close);subscriptions.clear() }
}
