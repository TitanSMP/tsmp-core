package de.titansmp.api.teleport

import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletionStage

enum class TeleportCooldownCategory { TPA, TPAHERE, HOME, RTP, CLAN_HOME }
enum class TeleportDirection { REQUESTER_TO_TARGET, TARGET_TO_REQUESTER }
enum class TeleportFailure { INVALID_NAME, ALREADY_EXISTS, NOT_FOUND, HOME_LIMIT, COOLDOWN, NO_PENDING_REQUEST, REQUEST_EXPIRED, SELF_REQUEST, OFFLINE, NOT_IN_CLAN, NOT_ADMIN, CLAN_HOME_LIMIT, UNSAFE_LOCATION, WORLD_UNAVAILABLE, TRANSFER_FAILED, INVALID_DESTINATION }
data class TeleportLocation(val world: String, val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float) {
    init { require(world.isNotBlank()); require(x.isFinite() && y.isFinite() && z.isFinite() && yaw.isFinite() && pitch.isFinite()) }
}
data class StoredTeleportLocation(val serverId: String, val location: TeleportLocation) { init { require(serverId.isNotBlank()) } }
data class TeleportHome(val ownerUuid: UUID, val normalizedName: String, val displayName: String, val destination: StoredTeleportLocation)
data class ClanTeleportHome(val clanUuid: UUID, val normalizedName: String, val displayName: String, val destination: StoredTeleportLocation)
data class TeleportRequest(val requestId: UUID, val requesterUuid: UUID, val targetUuid: UUID, val requesterName: String, val direction: TeleportDirection, val createdAt: Instant, val expiresAt: Instant)
data class TeleportOperation(val operationId: UUID, val playerUuid: UUID, val destination: StoredTeleportLocation)
data class CooldownStatus(val available: Boolean, val remainingSeconds: Long, val expiresAt: Instant?)
class TeleportOperationException(val failure: TeleportFailure, message: String = failure.name) : IllegalStateException(message)

interface TeleportService {
    fun requestTeleport(requesterUuid: UUID, targetUuid: UUID, requesterName: String, direction: TeleportDirection): CompletionStage<TeleportRequest>
    fun acceptTeleportRequest(targetUuid: UUID): CompletionStage<TeleportRequest>
    fun rejectTeleportRequest(targetUuid: UUID): CompletionStage<TeleportRequest>
    fun setHome(playerUuid: UUID, name: String, limit: Int?, destination: StoredTeleportLocation): CompletionStage<TeleportHome>
    fun deleteHome(playerUuid: UUID, name: String): CompletionStage<Boolean>
    fun getHome(playerUuid: UUID, name: String): CompletionStage<TeleportHome?>
    fun listHomes(playerUuid: UUID): CompletionStage<List<TeleportHome>>
    fun setClanHome(actorUuid: UUID, clanUuid: UUID, name: String, limit: Int?, destination: StoredTeleportLocation): CompletionStage<ClanTeleportHome>
    fun deleteClanHome(actorUuid: UUID, clanUuid: UUID, name: String): CompletionStage<Boolean>
    fun getClanHome(clanUuid: UUID, name: String?): CompletionStage<ClanTeleportHome?>
    fun listClanHomes(clanUuid: UUID): CompletionStage<List<ClanTeleportHome>>
    fun inspectCooldown(playerUuid: UUID, category: TeleportCooldownCategory, scope: String? = null): CompletionStage<CooldownStatus>
    fun reserveCooldown(playerUuid: UUID, category: TeleportCooldownCategory, scope: String? = null, cooldownMinutes: Int): CompletionStage<CooldownStatus>
    fun releaseCooldown(playerUuid: UUID, category: TeleportCooldownCategory, scope: String? = null): CompletionStage<Unit>
    fun completeOperation(operation: TeleportOperation): CompletionStage<Boolean>
    fun randomTeleport(playerUuid: UUID, eligibleServerTypes: Set<String>, locationProvider: SafeRandomLocationProvider): CompletionStage<TeleportOperation>
}
fun interface SafeRandomLocationProvider { fun findSafeLocation(maxAttempts: Int): CompletionStage<StoredTeleportLocation?> }

data class TeleportOperationCompleted(val operationId: UUID, val playerUuid: UUID, val serverId: String)

data class TeleportTransferRequested(
    override val metadata: de.titansmp.api.event.EventMetadata,
    val operationId: UUID,
    val playerUuid: UUID,
    val targetServerId: String?,
    val cooldownOwnerUuid: UUID,
    val cooldownCategory: TeleportCooldownCategory,
    val cooldownScope: String? = null,
    val targetServerType: String? = null,
) : de.titansmp.api.event.NetworkEvent {
    init { require((targetServerId != null) xor (targetServerType != null)); require(targetServerId?.isNotBlank() != false); require(targetServerType?.isNotBlank() != false) }
    companion object { val TYPE = de.titansmp.api.event.EventType("teleport.transfer_requested"); const val VERSION = 1 }
}

data class TeleportRequestCreated(
    override val metadata: de.titansmp.api.event.EventMetadata,
    val requestId: UUID,
    val requesterUuid: UUID,
    val targetUuid: UUID,
    val requesterName: String,
    val direction: TeleportDirection,
    val expiresAt: Instant,
) : de.titansmp.api.event.NetworkEvent {
    companion object { val TYPE = de.titansmp.api.event.EventType("teleport.request_created"); const val VERSION = 1 }
}

data class TeleportRequestResolved(
    override val metadata: de.titansmp.api.event.EventMetadata,
    val requestId: UUID,
    val requesterUuid: UUID,
    val targetUuid: UUID,
    val accepted: Boolean,
) : de.titansmp.api.event.NetworkEvent {
    companion object { val TYPE = de.titansmp.api.event.EventType("teleport.request_resolved"); const val VERSION = 1 }
}

data class TeleportLocationRequested(
    override val metadata: de.titansmp.api.event.EventMetadata,
    val operationId: UUID,
    val requesterUuid: UUID,
    val movingPlayerUuid: UUID,
) : de.titansmp.api.event.NetworkEvent {
    companion object { val TYPE = de.titansmp.api.event.EventType("teleport.location_requested"); const val VERSION = 1 }
}
