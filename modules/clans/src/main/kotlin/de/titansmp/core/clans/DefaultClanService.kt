package de.titansmp.core.clans

import de.titansmp.api.clans.*
import de.titansmp.api.event.EventBus
import de.titansmp.api.event.EventMetadata
import de.titansmp.api.friends.FriendService
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import java.util.logging.Level
import java.util.logging.Logger

data class ClanValidationConfig(
    val pageSize: Int = 10,
    val invitationExpirationHours: Long = 24,
    val nameMinLength: Int = 3,
    val nameMaxLength: Int = 24,
    val tagMinLength: Int = 2,
    val tagMaxLength: Int = 8,
) {
    init {
        require(pageSize in 1..100)
        require(invitationExpirationHours in 1..720)
        require(nameMinLength > 0 && nameMaxLength >= nameMinLength && nameMaxLength <= 24)
        require(tagMinLength > 0 && tagMaxLength >= tagMinLength && tagMaxLength <= 8)
    }
}

class DefaultClanService(
    private val repository: JdbcClanRepository,
    private val eventBus: EventBus,
    private val executor: Executor,
    private val serverId: String,
    private val config: ClanValidationConfig = ClanValidationConfig(),
    private val friendService: FriendService? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val logger: Logger = Logger.getLogger(DefaultClanService::class.java.name),
) : ClanService {
    override fun createClan(playerUuid: UUID, name: String, tag: String): CompletionStage<Clan> = db {
        val cleanName = name.trim(); val cleanTag = tag.trim()
        if (cleanName.length !in config.nameMinLength..config.nameMaxLength || !cleanName.matches(Regex("[A-Za-z0-9 _-]+"))) throw ClanOperationException(ClanFailure.INVALID_NAME)
        if (cleanTag.length !in config.tagMinLength..config.tagMaxLength || !cleanTag.matches(Regex("[A-Za-z0-9]+"))) throw ClanOperationException(ClanFailure.INVALID_TAG)
        if (repository.nameExists(cleanName)) throw ClanOperationException(ClanFailure.NAME_TAKEN)
        if (repository.tagExists(cleanTag)) throw ClanOperationException(ClanFailure.TAG_TAKEN)
        val clan = try { repository.create(playerUuid,cleanName,cleanTag,clock.instant()) } catch (failure: java.sql.SQLException) {
            when { repository.nameExists(cleanName) -> throw ClanOperationException(ClanFailure.NAME_TAKEN); repository.tagExists(cleanTag) -> throw ClanOperationException(ClanFailure.TAG_TAKEN); else -> throw failure }
        }
        publish(clanEvent(ClanEventAction.CREATED,clan,playerUuid,target=playerUuid,role=ClanRole.ADMIN,affected=listOf(playerUuid)))
        clan
    }
    override fun disbandClan(playerUuid: UUID): CompletionStage<Clan> = db {
        val (clan,members)=repository.disband(playerUuid)
        publish(clanEvent(ClanEventAction.DISBANDED,clan,playerUuid,affected=members))
        clan
    }
    override fun getClan(clanUuid: UUID) = db { repository.get(clanUuid) }
    override fun getClanByName(name: String) = db { repository.byName(name) }
    override fun getClanByMember(playerUuid: UUID) = db { repository.byMember(playerUuid) }
    override fun promote(actorUuid: UUID,targetUuid: UUID):CompletionStage<ClanRole> = db {
        val role=repository.promote(actorUuid,targetUuid); val clan=requireNotNull(repository.byMember(actorUuid)); publish(clanEvent(ClanEventAction.MEMBER_ROLE_CHANGED,clan,actorUuid,targetUuid,role)); role
    }
    override fun demote(actorUuid: UUID,targetUuid: UUID):CompletionStage<ClanRole> = db {
        val role=repository.demote(actorUuid,targetUuid); val clan=requireNotNull(repository.byMember(actorUuid)); publish(clanEvent(ClanEventAction.MEMBER_ROLE_CHANGED,clan,actorUuid,targetUuid,role)); role
    }
    override fun kick(actorUuid: UUID,targetUuid: UUID):CompletionStage<Unit> = db {
        val (clan,_)=repository.kick(actorUuid,targetUuid); publish(clanEvent(ClanEventAction.MEMBER_KICKED,clan,actorUuid,targetUuid)); Unit
    }
    override fun leave(playerUuid: UUID):CompletionStage<Unit> = db {
        val (clan,empty)=repository.leave(playerUuid)
        if (empty) publish(clanEvent(ClanEventAction.DISBANDED,clan,playerUuid,affected=listOf(playerUuid)))
        else publish(clanEvent(ClanEventAction.MEMBER_LEFT,clan,playerUuid,playerUuid))
        Unit
    }
    override fun invite(actorUuid: UUID,targetUuid: UUID):CompletionStage<ClanInvitation> = notBlocked(actorUuid,targetUuid).thenComposeAsync({ db {
        val now=clock.instant(); val invite=repository.invite(actorUuid,targetUuid,now,now.plus(Duration.ofHours(config.invitationExpirationHours)))
        val clan=Clan(invite.clanUuid,invite.clanName,invite.clanTag,ClanColor(ClanColorMode.STANDARD,"white"),now)
        publish(clanEvent(ClanEventAction.INVITATION_CREATED,clan,actorUuid,targetUuid)); invite
    } },executor)
    override fun acceptInvitation(playerUuid: UUID,clanUuid: UUID):CompletionStage<Clan> = CompletableFuture.supplyAsync({ repository.invitationInviter(playerUuid,clanUuid) },executor).thenCompose { inviter ->
        if(inviter==null) db { repository.accept(playerUuid,clanUuid,clock.instant()) }
        else notBlocked(playerUuid,inviter).thenComposeAsync({ db { repository.accept(playerUuid,clanUuid,clock.instant()) } },executor)
    }.thenApply { clan ->
        publish(clanEvent(ClanEventAction.INVITATION_ACCEPTED,clan,playerUuid,playerUuid,ClanRole.MEMBER))
        publish(clanEvent(ClanEventAction.MEMBER_JOINED,clan,playerUuid,playerUuid,ClanRole.MEMBER))
        clan
    }
    override fun rejectInvitation(playerUuid: UUID,clanUuid: UUID):CompletionStage<Unit> = db {
        val inviter=repository.reject(playerUuid,clanUuid); val clan=requireNotNull(repository.get(clanUuid))
        publish(clanEvent(ClanEventAction.INVITATION_REJECTED,clan,playerUuid,inviter)); Unit
    }
    override fun listInvitations(playerUuid: UUID,page: Int,pageSize: Int) = db { repository.invitations(playerUuid,page,pageSize,clock.instant()) }
    override fun listMembers(clanUuid: UUID,page: Int,pageSize: Int) = db { repository.members(clanUuid,page,pageSize) }
    override fun setColor(playerUuid: UUID,color: ClanColor):CompletionStage<ClanColor> = db {
        if (color.color.lowercase() !in COLORS) throw ClanOperationException(ClanFailure.INVALID_COLOR,"Unknown clan color")
        val normalized=color.copy(color=color.color.lowercase())
        val clan=repository.setColor(playerUuid,normalized)
        publish(clanEvent(ClanEventAction.COLOR_CHANGED,clan,playerUuid))
        normalized
    }
    override fun getColor(clanUuid: UUID):CompletionStage<ClanColor?> = getClan(clanUuid).thenApply { it?.color }
    override fun sendClanChat(playerUuid: UUID,playerName: String,message: String):CompletionStage<Boolean> = db {
        val clean=message.trim().take(256); if(clean.isBlank()) return@db false
        val clan=repository.byMember(playerUuid) ?: return@db false
        val event=ClanChatMessage(meta(ClanChatMessage.TYPE),clan.uuid,clan.tag,clan.color.mode,clan.color.color,playerUuid,playerName,clean)
        publish(event); true
    }

    private fun clanEvent(action:ClanEventAction,clan:Clan,actor:UUID,target:UUID?=null,role:ClanRole?=null,affected:List<UUID> = emptyList()) = ClanChanged(meta(ClanChanged.TYPES.getValue(action)),action,clan.uuid,actor,target,role,clan.name,clan.tag,affected)
    private fun meta(type:de.titansmp.api.event.EventType)=EventMetadata(eventType=type,sourceServerId=serverId)
    private fun publish(event:de.titansmp.api.event.NetworkEvent) {
        try { eventBus.publish(event).whenComplete { _,failure -> if(failure!=null) logger.log(Level.WARNING,"Clan state committed but ${event.metadata.eventType.value} publish failed",failure) } }
        catch (failure:Throwable) { logger.log(Level.WARNING,"Clan state committed but ${event.metadata.eventType.value} publish failed",failure) }
    }
    private fun <T> db(block:()->T):CompletionStage<T> = CompletableFuture.supplyAsync(block,executor)
    private fun notBlocked(a:UUID,b:UUID):CompletionStage<Unit> {
        val friends=friendService ?: return CompletableFuture.completedFuture(Unit)
        return friends.isBlocked(a,b).thenCombine(friends.isBlocked(b,a)) { first,second ->
            if(first || second) throw ClanOperationException(ClanFailure.TARGET_BLOCKED,"Clan invitations are unavailable for that player.")
            Unit
        }
    }
    companion object { val COLORS=setOf("black","dark_blue","dark_green","dark_aqua","dark_red","dark_purple","gold","gray","dark_gray","blue","green","aqua","red","light_purple","yellow","white") }
}
