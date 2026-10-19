package de.titansmp.core.moderation

import de.titansmp.api.moderation.ActivePunishment
import de.titansmp.api.moderation.ModerationActionType
import de.titansmp.api.moderation.ModerationActor
import de.titansmp.api.moderation.ModerationTarget
import de.titansmp.api.moderation.PunishmentType
import java.time.Instant
import java.util.UUID

interface ModerationRepository {
    fun savePunishment(punishment: ActivePunishment, action: ModerationActionType)
    fun removePunishment(target: ModerationTarget, type: PunishmentType, actor: ModerationActor, action: ModerationActionType, reason: String?, actionId: UUID, at: Instant, serverId: String): Boolean
    fun recordKick(target: ModerationTarget, actor: ModerationActor, reason: String, actionId: UUID, at: Instant, serverId: String)
    fun findActive(targetId: UUID, type: PunishmentType, now: Instant): ActivePunishment?
}
