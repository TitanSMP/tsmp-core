package de.titansmp.api.moderation

import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletionStage

enum class PunishmentType { BAN, MUTE }
enum class ModerationActionType { BAN, UNBAN, MUTE, UNMUTE, KICK }

data class ModerationActor(val uuid: UUID?, val name: String) {
    init { require(name.isNotBlank()) }
}

/** Stable network identity and the most recently known display-name snapshot. */
data class ModerationTarget(val uuid: UUID, val name: String) {
    init { require(name.isNotBlank()) }
}

data class ActivePunishment(
    val id: UUID,
    val target: ModerationTarget,
    val type: PunishmentType,
    val reason: String,
    val createdAt: Instant,
    val expiresAt: Instant?,
    val actor: ModerationActor,
    val serverId: String,
)

interface ModerationService {
    fun ban(actor: ModerationActor, target: ModerationTarget, reason: String, serverId: String): CompletionStage<ActivePunishment>
    fun unban(actor: ModerationActor, target: ModerationTarget, reason: String?, serverId: String): CompletionStage<Unit>
    fun mute(actor: ModerationActor, target: ModerationTarget, duration: java.time.Duration?, reason: String, serverId: String): CompletionStage<ActivePunishment>
    fun unmute(actor: ModerationActor, target: ModerationTarget, reason: String?, serverId: String): CompletionStage<Unit>
    fun kick(actor: ModerationActor, target: ModerationTarget, reason: String, serverId: String): CompletionStage<Unit>
    fun checkPunishment(targetId: UUID, type: PunishmentType): CompletionStage<ActivePunishment?>
    fun isMuted(targetId: UUID): CompletionStage<Boolean> = checkPunishment(targetId, PunishmentType.MUTE).thenApply { it != null }
}

/** Platform adapter delegates permission semantics (including wildcards) to LuckPerms. */
fun interface ModerationPermissionService {
    fun hasPermission(playerId: UUID, permission: String): CompletionStage<Boolean>
}

class ModerationException(val code: Code, message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    enum class Code { NO_PERMISSION, IMMUNE_TARGET, SELF_TARGET, NO_ACTIVE_PUNISHMENT, PERMISSION_CHECK_FAILED, DATABASE_FAILURE }
}
