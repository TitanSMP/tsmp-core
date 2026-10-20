package de.titansmp.api.clans

import de.titansmp.api.event.EventMetadata
import de.titansmp.api.event.EventType
import de.titansmp.api.event.NetworkEvent
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletionStage

enum class ClanRole { MEMBER, MODERATOR, ADMIN }
enum class ClanColorMode { STANDARD, RAINBOW }
data class ClanColor(val mode: ClanColorMode, val color: String)
data class Clan(val uuid: UUID, val name: String, val tag: String, val color: ClanColor, val createdAt: Instant)
data class ClanMember(val clanUuid: UUID, val playerUuid: UUID, val role: ClanRole, val joinedAt: Instant)
data class ClanInvitation(val clanUuid: UUID, val clanName: String, val clanTag: String, val playerUuid: UUID, val inviterUuid: UUID, val createdAt: Instant, val expiresAt: Instant)
data class ClanPage<T>(val entries: List<T>, val page: Int, val pageSize: Int, val total: Long) {
    val pageCount: Int get() = if (total == 0L) 0 else ((total + pageSize - 1) / pageSize).toInt()
}

enum class ClanFailure { ALREADY_IN_CLAN, NAME_TAKEN, TAG_TAKEN, INVALID_NAME, INVALID_TAG, INVALID_COLOR, CLAN_NOT_FOUND, NOT_IN_CLAN, NOT_ADMIN, NOT_MODERATOR, TARGET_NOT_MEMBER, TARGET_IN_CLAN, SELF_TARGET, ALREADY_ADMIN, LAST_ADMIN, CANNOT_KICK_ADMIN, INVITATION_EXISTS, INVITATION_NOT_FOUND, INVITATION_EXPIRED, TARGET_BLOCKED }
class ClanOperationException(val reason: ClanFailure, message: String = reason.name) : IllegalStateException(message)

interface ClanService {
    fun createClan(playerUuid: UUID, name: String, tag: String): CompletionStage<Clan>
    fun disbandClan(playerUuid: UUID): CompletionStage<Clan>
    fun getClan(clanUuid: UUID): CompletionStage<Clan?>
    fun getClanByName(name: String): CompletionStage<Clan?>
    fun getClanByMember(playerUuid: UUID): CompletionStage<Clan?>
    fun promote(actorUuid: UUID, targetUuid: UUID): CompletionStage<ClanRole>
    fun demote(actorUuid: UUID, targetUuid: UUID): CompletionStage<ClanRole>
    fun kick(actorUuid: UUID, targetUuid: UUID): CompletionStage<Unit>
    fun leave(playerUuid: UUID): CompletionStage<Unit>
    fun invite(actorUuid: UUID, targetUuid: UUID): CompletionStage<ClanInvitation>
    fun acceptInvitation(playerUuid: UUID, clanUuid: UUID): CompletionStage<Clan>
    fun rejectInvitation(playerUuid: UUID, clanUuid: UUID): CompletionStage<Unit>
    fun listInvitations(playerUuid: UUID, page: Int, pageSize: Int): CompletionStage<ClanPage<ClanInvitation>>
    fun listMembers(clanUuid: UUID, page: Int = 1, pageSize: Int = 100): CompletionStage<ClanPage<ClanMember>>
    fun setColor(playerUuid: UUID, color: ClanColor): CompletionStage<ClanColor>
    fun getColor(clanUuid: UUID): CompletionStage<ClanColor?>
    fun sendClanChat(playerUuid: UUID, playerName: String, message: String): CompletionStage<Boolean>
}

enum class ClanEventAction { CREATED, DISBANDED, MEMBER_JOINED, MEMBER_LEFT, MEMBER_KICKED, MEMBER_ROLE_CHANGED, INVITATION_CREATED, INVITATION_ACCEPTED, INVITATION_REJECTED, COLOR_CHANGED }
data class ClanChanged(
    override val metadata: EventMetadata,
    val action: ClanEventAction,
    val clanUuid: UUID,
    val actorUuid: UUID,
    val targetUuid: UUID? = null,
    val role: ClanRole? = null,
    val clanName: String = "",
    val clanTag: String = "",
    val affectedMembers: List<UUID> = emptyList(),
) : NetworkEvent {
    companion object {
        const val VERSION = 1
        val TYPES = ClanEventAction.entries.associateWith { action -> EventType("clan.${when (action) {
            ClanEventAction.CREATED -> "created"; ClanEventAction.DISBANDED -> "disbanded"; ClanEventAction.MEMBER_JOINED -> "member_joined"; ClanEventAction.MEMBER_LEFT -> "member_left"; ClanEventAction.MEMBER_KICKED -> "member_kicked"; ClanEventAction.MEMBER_ROLE_CHANGED -> "member_role_changed"; ClanEventAction.INVITATION_CREATED -> "invitation_created"; ClanEventAction.INVITATION_ACCEPTED -> "invitation_accepted"; ClanEventAction.INVITATION_REJECTED -> "invitation_rejected"; ClanEventAction.COLOR_CHANGED -> "color_changed"
        }}") }
    }
}
data class ClanChatMessage(override val metadata: EventMetadata, val clanUuid: UUID, val clanTag: String, val colorMode: ClanColorMode, val colorValue: String, val senderUuid: UUID, val senderName: String, val message: String) : NetworkEvent {
    companion object { const val VERSION = 1; val TYPE = EventType("clan.chat_message") }
}
