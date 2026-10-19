package de.titansmp.core.moderation

import de.titansmp.api.event.EventBus
import de.titansmp.api.event.EventMetadata
import de.titansmp.api.moderation.ActivePunishment
import de.titansmp.api.moderation.ModerationActionType
import de.titansmp.api.moderation.ModerationActor
import de.titansmp.api.moderation.ModerationException
import de.titansmp.api.moderation.ModerationPermissionService
import de.titansmp.api.moderation.ModerationService
import de.titansmp.api.moderation.ModerationTarget
import de.titansmp.api.moderation.PlayerBanned
import de.titansmp.api.moderation.PlayerKicked
import de.titansmp.api.moderation.PlayerMuted
import de.titansmp.api.moderation.PlayerUnbanned
import de.titansmp.api.moderation.PlayerUnmuted
import de.titansmp.api.moderation.PunishmentType
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor

/** Shared moderation logic. Platform commands and network services must use this implementation. */
class DefaultModerationService(
    private val repository: ModerationRepository,
    private val permissions: ModerationPermissionService,
    private val eventBus: EventBus,
    private val executor: Executor,
    private val clock: Clock = Clock.systemUTC(),
    private val logger: java.util.logging.Logger = java.util.logging.Logger.getLogger(DefaultModerationService::class.java.name),
) : ModerationService {
    override fun ban(actor: ModerationActor, target: ModerationTarget, reason: String, serverId: String) = authorize(actor, target, "ban", punish = true).thenCompose {
        val now = clock.instant()
        val punishment = ActivePunishment(UUID.randomUUID(), target, PunishmentType.BAN, reason.cleanReason(), now, null, actor, serverId)
        onWorker { repository.savePunishment(punishment, ModerationActionType.BAN); punishment }
            .thenCompose { saved -> publish(PlayerBanned(EventMetadata(eventType = PlayerBanned.TYPE, sourceServerId = serverId), target.uuid, target.name, saved.id, actor.uuid, actor.name, saved.reason, saved.createdAt, saved.expiresAt, serverId), saved) }
    }

    override fun unban(actor: ModerationActor, target: ModerationTarget, reason: String?, serverId: String) = authorize(actor, target, "unban", punish = false).thenCompose {
        val actionId = UUID.randomUUID(); val now = clock.instant()
        onWorker { if (!repository.removePunishment(target, PunishmentType.BAN, actor, ModerationActionType.UNBAN, reason?.cleanOptionalReason(), actionId, now, serverId)) throw noActive("ban", target) }
            .thenCompose { publish(PlayerUnbanned(EventMetadata(eventType = PlayerUnbanned.TYPE, sourceServerId = serverId), target.uuid, target.name, actionId, actor.uuid, actor.name, reason?.cleanOptionalReason(), now, serverId), Unit) }
    }

    override fun mute(actor: ModerationActor, target: ModerationTarget, duration: Duration?, reason: String, serverId: String) = authorize(actor, target, "mute", punish = true).thenCompose {
        val now = clock.instant()
        val expiry = duration?.let { runCatching { now.plus(it) }.getOrElse { throw IllegalArgumentException("Mute duration is too large") } }
        val punishment = ActivePunishment(UUID.randomUUID(), target, PunishmentType.MUTE, reason.cleanReason(), now, expiry, actor, serverId)
        onWorker { repository.savePunishment(punishment, ModerationActionType.MUTE); punishment }
            .thenCompose { saved -> publish(PlayerMuted(EventMetadata(eventType = PlayerMuted.TYPE, sourceServerId = serverId), target.uuid, target.name, saved.id, actor.uuid, actor.name, saved.reason, saved.createdAt, saved.expiresAt, serverId), saved) }
    }

    override fun unmute(actor: ModerationActor, target: ModerationTarget, reason: String?, serverId: String) = authorize(actor, target, "unmute", punish = false).thenCompose {
        val actionId = UUID.randomUUID(); val now = clock.instant()
        onWorker { if (!repository.removePunishment(target, PunishmentType.MUTE, actor, ModerationActionType.UNMUTE, reason?.cleanOptionalReason(), actionId, now, serverId)) throw noActive("mute", target) }
            .thenCompose { publish(PlayerUnmuted(EventMetadata(eventType = PlayerUnmuted.TYPE, sourceServerId = serverId), target.uuid, target.name, actionId, actor.uuid, actor.name, reason?.cleanOptionalReason(), now, serverId), Unit) }
    }

    override fun kick(actor: ModerationActor, target: ModerationTarget, reason: String, serverId: String) = authorize(actor, target, "kick", punish = true).thenCompose {
        val actionId = UUID.randomUUID(); val now = clock.instant(); val cleanReason = reason.cleanReason()
        onWorker { repository.recordKick(target, actor, cleanReason, actionId, now, serverId) }
            .thenCompose { publish(PlayerKicked(EventMetadata(eventType = PlayerKicked.TYPE, sourceServerId = serverId), target.uuid, target.name, actionId, actor.uuid, actor.name, cleanReason, now, serverId), Unit) }
    }

    override fun checkPunishment(targetId: UUID, type: PunishmentType): CompletionStage<ActivePunishment?> =
        onWorker { repository.findActive(targetId, type, clock.instant()) }.exceptionally { failure -> throw databaseFailure(failure) }

    private fun authorize(actor: ModerationActor, target: ModerationTarget, permission: String, punish: Boolean): CompletionStage<Unit> {
        val actorId = actor.uuid ?: return CompletableFuture.completedFuture(Unit)
        return permissions.hasPermission(actorId, "titansmp.moderation.$permission").thenCompose { allowed ->
            if (!allowed) return@thenCompose failed(ModerationException(ModerationException.Code.NO_PERMISSION, "You do not have permission to $permission players."))
            if (!punish) return@thenCompose CompletableFuture.completedFuture(Unit)
            if (actorId == target.uuid) return@thenCompose failed(ModerationException(ModerationException.Code.SELF_TARGET, "You cannot $permission yourself."))
            permissions.hasPermission(target.uuid, IMMUNITY_PERMISSION).thenCompose { immune ->
                if (immune) failed(ModerationException(ModerationException.Code.IMMUNE_TARGET, "That player is immune to moderation."))
                else CompletableFuture.completedFuture(Unit)
            }
        }.exceptionallyCompose { failure ->
            val cause = unwrap(failure)
            failed(if (cause is ModerationException) cause else ModerationException(ModerationException.Code.PERMISSION_CHECK_FAILED, "LuckPerms could not verify moderation permissions.", cause))
        }
    }

    private fun <T> publish(event: de.titansmp.api.event.NetworkEvent, result: T): CompletionStage<T> = try {
        eventBus.publish(event).handle { _, failure ->
            if (failure != null) logger.log(java.util.logging.Level.SEVERE, "Moderation action persisted but Redis event ${event.metadata.eventType.value} could not be published", failure)
            result
        }
    } catch (failure: Throwable) {
        logger.log(java.util.logging.Level.SEVERE, "Moderation action persisted but Redis event ${event.metadata.eventType.value} could not be published", failure)
        CompletableFuture.completedFuture(result)
    }

    private fun <T> onWorker(block: () -> T): CompletionStage<T> = CompletableFuture.supplyAsync(block, executor).exceptionallyCompose { failure -> failed(databaseFailure(failure)) }
    private fun databaseFailure(failure: Throwable) = ModerationException(ModerationException.Code.DATABASE_FAILURE, "Moderation data could not be saved or loaded.", unwrap(failure))
    private fun noActive(type: String, target: ModerationTarget) = ModerationException(ModerationException.Code.NO_ACTIVE_PUNISHMENT, "${target.name} has no active $type.")
    private fun unwrap(failure: Throwable): Throwable = failure.cause ?: failure
    private fun <T> failed(failure: Throwable): CompletionStage<T> = CompletableFuture.failedFuture(failure)
    private fun String.cleanReason(): String = trim().ifBlank { "No reason provided" }.take(512)
    private fun String.cleanOptionalReason(): String? = trim().takeIf(String::isNotEmpty)?.take(512)

    companion object {
        const val IMMUNITY_PERMISSION = "titansmp.moderation.immunity"
    }
}
