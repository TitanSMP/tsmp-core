package de.titansmp.core.teleport

import de.titansmp.api.teleport.*
import de.titansmp.core.redis.RedisService
import io.lettuce.core.SetArgs
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor

class DefaultTeleportService(
    private val repository: JdbcTeleportRepository,
    private val redis: RedisService,
    private val executor: Executor,
    private val defaultRequestExpirationSeconds: Long = 60,
    private val defaultCooldownMinutes: Int = 30,
    private val maxRtpAttempts: Int = 20,
    private val clock: Clock = Clock.systemUTC(),
) : TeleportService {
    init { require(defaultRequestExpirationSeconds in 1..3600); require(defaultCooldownMinutes >= 0); require(maxRtpAttempts in 1..100) }
    override fun requestTeleport(requesterUuid: UUID,targetUuid: UUID,requesterName:String,direction:TeleportDirection):CompletionStage<TeleportRequest> = async {
        if(requesterUuid==targetUuid) throw TeleportOperationException(TeleportFailure.SELF_REQUEST)
        val now=clock.instant()
        repository.createRequest(TeleportRequest(UUID.randomUUID(),requesterUuid,targetUuid,requesterName,direction,now,now.plusSeconds(defaultRequestExpirationSeconds)))
    }
    override fun acceptTeleportRequest(targetUuid:UUID)=async { repository.resolveRequest(targetUuid,clock.instant()) }
    override fun rejectTeleportRequest(targetUuid:UUID)=async { repository.resolveRequest(targetUuid,clock.instant()) }
    override fun setHome(playerUuid:UUID,name:String,limit:Int?,destination:StoredTeleportLocation)=async { repository.setHome(playerUuid,normalize(name),name.trim(),limit,destination) }
    override fun deleteHome(playerUuid:UUID,name:String)=async { repository.deleteHome(playerUuid,normalize(name)) }
    override fun getHome(playerUuid:UUID,name:String)=async { repository.getHome(playerUuid,normalize(name)) }
    override fun listHomes(playerUuid:UUID)=async { repository.listHomes(playerUuid) }
    override fun setClanHome(actorUuid:UUID,clanUuid:UUID,name:String,limit:Int?,destination:StoredTeleportLocation)=async { repository.setClanHome(actorUuid,clanUuid,normalize(name),name.trim(),limit,destination) }
    override fun deleteClanHome(actorUuid:UUID,clanUuid:UUID,name:String)=async { repository.deleteClanHome(actorUuid,clanUuid,normalize(name)) }
    override fun getClanHome(clanUuid:UUID,name:String?)=async { repository.getClanHome(clanUuid,name?.let(::normalize)) }
    override fun listClanHomes(clanUuid:UUID)=async { repository.listClanHomes(clanUuid) }
    override fun inspectCooldown(playerUuid:UUID,category:TeleportCooldownCategory,scope:String?):CompletionStage<CooldownStatus> = async {
        val ttl=redis.commands.pttl(TeleportCooldownKeys.key(playerUuid,category,scope))
        val remaining=ttl.coerceAtLeast(0L)
        CooldownStatus(ttl<0, (remaining+999)/1000, if(ttl>0) clock.instant().plusMillis(ttl) else null)
    }
    override fun reserveCooldown(playerUuid:UUID,category:TeleportCooldownCategory,scope:String?,cooldownMinutes:Int):CompletionStage<CooldownStatus> = async {
        require(cooldownMinutes>=0)
        val key=TeleportCooldownKeys.key(playerUuid,category,scope)
        val millis=Duration.ofMinutes(cooldownMinutes.toLong()).toMillis()
        val result=redis.commands.set(key,"1",SetArgs.Builder.nx().px(millis.coerceAtLeast(1)))
        if(result=="OK") CooldownStatus(true,0,null) else {
            val ttl=redis.commands.pttl(key).coerceAtLeast(0)
            CooldownStatus(false,(ttl+999)/1000,clock.instant().plusMillis(ttl))
        }
    }
    override fun releaseCooldown(playerUuid:UUID,category:TeleportCooldownCategory,scope:String?):CompletionStage<Unit> = async { redis.commands.del(TeleportCooldownKeys.key(playerUuid,category,scope)).let { Unit } }
    override fun completeOperation(operation:TeleportOperation):CompletionStage<Boolean> = async {
        redis.commands.set("titansmp:teleport:completed:${operation.playerUuid}:${operation.operationId}","1",SetArgs.Builder.nx().ex(600))=="OK"
    }
    override fun randomTeleport(playerUuid:UUID,eligibleServerTypes:Set<String>,locationProvider:SafeRandomLocationProvider):CompletionStage<TeleportOperation> =
        locationProvider.findSafeLocation(maxRtpAttempts).thenComposeAsync({ destination ->
            if(destination==null) CompletableFuture.failedFuture(TeleportOperationException(TeleportFailure.UNSAFE_LOCATION))
            else CompletableFuture.completedFuture(TeleportOperation(UUID.randomUUID(),playerUuid,destination))
        },executor)
    private fun normalize(raw:String):String { val n=raw.trim().lowercase();if(n.length !in 1..32 || !n.matches(Regex("[a-z0-9_-]+"))) throw TeleportOperationException(TeleportFailure.INVALID_NAME);return n }
    private fun <T> async(block:()->T):CompletionStage<T> = CompletableFuture.supplyAsync(block,executor)
}
