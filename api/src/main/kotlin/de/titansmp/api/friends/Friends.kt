package de.titansmp.api.friends

import de.titansmp.api.event.EventMetadata
import de.titansmp.api.event.EventType
import de.titansmp.api.event.NetworkEvent
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletionStage

data class Friend(val playerUuid: UUID, val createdAt: Instant)
data class FriendRequest(val requesterUuid: UUID, val targetUuid: UUID, val createdAt: Instant)
data class FriendsPage<T>(val entries: List<T>, val page: Int, val pageSize: Int, val total: Long) {
    val pageCount: Int get() = if (total == 0L) 0 else ((total + pageSize - 1) / pageSize).toInt()
}
enum class FriendRequestResult { CREATED, INCOMING_REQUEST_EXISTS, ALREADY_FRIENDS, BLOCKED, SELF, DUPLICATE }
enum class FriendshipState { NOT_FRIENDS, FRIENDS, BLOCKED_BY_SELF, BLOCKED_BY_OTHER }

interface FriendService {
    fun sendRequest(senderUuid: UUID, targetUuid: UUID): CompletionStage<FriendRequestResult>
    fun sendRequest(senderUuid: UUID, targetUuid: UUID, senderName: String): CompletionStage<FriendRequestResult> = sendRequest(senderUuid, targetUuid)
    fun acceptRequest(playerUuid: UUID, requesterUuid: UUID): CompletionStage<Boolean>
    fun rejectRequest(playerUuid: UUID, requesterUuid: UUID): CompletionStage<Boolean>
    fun removeFriend(playerUuid: UUID, friendUuid: UUID): CompletionStage<Boolean>
    fun blockPlayer(playerUuid: UUID, targetUuid: UUID): CompletionStage<Boolean>
    fun unblockPlayer(playerUuid: UUID, targetUuid: UUID): CompletionStage<Boolean>
    fun getFriends(playerUuid: UUID, page: Int, pageSize: Int): CompletionStage<FriendsPage<Friend>>
    fun getIncomingRequests(playerUuid: UUID, page: Int, pageSize: Int): CompletionStage<FriendsPage<FriendRequest>>
    fun areFriends(playerUuid: UUID, otherUuid: UUID): CompletionStage<Boolean>
    fun isBlocked(playerUuid: UUID, otherUuid: UUID): CompletionStage<Boolean>
}

data class FriendRequestCreated(override val metadata: EventMetadata, val requesterUuid: UUID, val targetUuid: UUID, val requesterName: String) : NetworkEvent {
    companion object { val TYPE = EventType("friend.request_created"); const val VERSION = 1 }
}
data class FriendRequestAccepted(override val metadata: EventMetadata, val playerUuid: UUID, val requesterUuid: UUID) : NetworkEvent {
    companion object { val TYPE = EventType("friend.request_accepted"); const val VERSION = 1 }
}
data class FriendRequestRejected(override val metadata: EventMetadata, val playerUuid: UUID, val requesterUuid: UUID) : NetworkEvent {
    companion object { val TYPE = EventType("friend.request_rejected"); const val VERSION = 1 }
}
data class FriendRemoved(override val metadata: EventMetadata, val playerUuid: UUID, val friendUuid: UUID) : NetworkEvent {
    companion object { val TYPE = EventType("friend.removed"); const val VERSION = 1 }
}
data class FriendBlocked(override val metadata: EventMetadata, val blockerUuid: UUID, val blockedUuid: UUID) : NetworkEvent {
    companion object { val TYPE = EventType("friend.blocked"); const val VERSION = 1 }
}
data class FriendUnblocked(override val metadata: EventMetadata, val blockerUuid: UUID, val blockedUuid: UUID) : NetworkEvent {
    companion object { val TYPE = EventType("friend.unblocked"); const val VERSION = 1 }
}
