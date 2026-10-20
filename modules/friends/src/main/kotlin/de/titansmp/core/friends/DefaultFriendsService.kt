package de.titansmp.core.friends

import de.titansmp.api.event.EventBus
import de.titansmp.api.event.EventMetadata
import de.titansmp.api.friends.*
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import java.util.logging.Level
import java.util.logging.Logger

class DefaultFriendsService(
    private val repository: JdbcFriendsRepository,
    private val eventBus: EventBus,
    private val executor: Executor,
    private val serverId: String,
    private val logger: Logger = Logger.getLogger(DefaultFriendsService::class.java.name),
) : FriendService {
    override fun sendRequest(senderUuid: UUID, targetUuid: UUID): CompletionStage<FriendRequestResult> = sendRequest(senderUuid, targetUuid, "")
    override fun sendRequest(senderUuid: UUID, targetUuid: UUID, senderName: String): CompletionStage<FriendRequestResult> = db {
        val result = repository.send(senderUuid, targetUuid)
        if (result == FriendRequestResult.CREATED) publish(FriendRequestCreated(meta(FriendRequestCreated.TYPE), senderUuid, targetUuid, senderName))
        result
    }
    override fun acceptRequest(playerUuid: UUID, requesterUuid: UUID): CompletionStage<Boolean> = db {
        val accepted = repository.accept(playerUuid, requesterUuid)
        if (accepted) publish(FriendRequestAccepted(meta(FriendRequestAccepted.TYPE), playerUuid, requesterUuid))
        accepted
    }
    override fun rejectRequest(playerUuid: UUID, requesterUuid: UUID): CompletionStage<Boolean> = db {
        val rejected = repository.reject(playerUuid, requesterUuid)
        if (rejected) publish(FriendRequestRejected(meta(FriendRequestRejected.TYPE), playerUuid, requesterUuid))
        rejected
    }
    override fun removeFriend(playerUuid: UUID, friendUuid: UUID): CompletionStage<Boolean> = db {
        val removed = repository.remove(playerUuid, friendUuid)
        if (removed) publish(FriendRemoved(meta(FriendRemoved.TYPE), playerUuid, friendUuid))
        removed
    }
    override fun blockPlayer(playerUuid: UUID, targetUuid: UUID): CompletionStage<Boolean> = db {
        val created = repository.block(playerUuid, targetUuid)
        if (created) publish(FriendBlocked(meta(FriendBlocked.TYPE), playerUuid, targetUuid))
        created
    }
    override fun unblockPlayer(playerUuid: UUID, targetUuid: UUID): CompletionStage<Boolean> = db {
        val removed = repository.unblock(playerUuid, targetUuid)
        if (removed) publish(FriendUnblocked(meta(FriendUnblocked.TYPE), playerUuid, targetUuid))
        removed
    }
    override fun getFriends(playerUuid: UUID, page: Int, pageSize: Int) = db { repository.friends(playerUuid, page, pageSize) }
    override fun getIncomingRequests(playerUuid: UUID, page: Int, pageSize: Int) = db { repository.requests(playerUuid, page, pageSize) }
    override fun areFriends(playerUuid: UUID, otherUuid: UUID) = db { repository.areFriends(playerUuid, otherUuid) }
    override fun isBlocked(playerUuid: UUID, otherUuid: UUID) = db { repository.isBlocked(playerUuid, otherUuid) }

    private fun meta(type: de.titansmp.api.event.EventType) = EventMetadata(eventType = type, sourceServerId = serverId)
    private fun publish(event: de.titansmp.api.event.NetworkEvent) {
        try { eventBus.publish(event).whenComplete { _, failure -> if (failure != null) logger.log(Level.SEVERE, "Friend state committed but ${event.metadata.eventType.value} could not be published", failure) } }
        catch (failure: Throwable) { logger.log(Level.SEVERE, "Friend state committed but ${event.metadata.eventType.value} could not be published", failure) }
    }
    private fun <T> db(block: () -> T): CompletionStage<T> = CompletableFuture.supplyAsync(block, executor)
}
