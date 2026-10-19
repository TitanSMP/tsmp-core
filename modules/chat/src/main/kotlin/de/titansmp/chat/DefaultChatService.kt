package de.titansmp.chat

import de.titansmp.api.chat.ChatException
import de.titansmp.api.chat.ChatMessage
import de.titansmp.api.chat.ChatMessageSource
import de.titansmp.api.chat.ChatService
import de.titansmp.api.chat.NetworkChatMessage
import de.titansmp.api.event.EventBus
import de.titansmp.api.event.EventMetadata
import de.titansmp.api.moderation.ModerationPermissionService
import de.titansmp.api.moderation.ModerationService
import de.titansmp.core.config.ChatConfig
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

class DefaultChatService(
    private val eventBus: EventBus,
    private val moderation: ModerationService,
    private val permissions: ModerationPermissionService,
    private val config: ChatConfig,
    private val clock: Clock = Clock.systemUTC(),
    private val limiter: ChatRateLimiter = ChatRateLimiter(config.messagesPerWindow, config.windowMs, config.rateLimitEnabled, clock),
) : ChatService {
    override fun send(senderUuid: UUID, senderName: String, message: String, serverId: String): CompletionStage<ChatMessage> {
        val chatMessage = try {
            ChatMessage(UUID.randomUUID(), senderUuid, senderName, message, serverId, clock.instant(), ChatMessageSource.MINECRAFT).also(::validate)
        } catch (failure: ChatException) {
            return CompletableFuture.failedFuture(failure)
        } catch (failure: IllegalArgumentException) {
            return CompletableFuture.failedFuture(ChatException(ChatException.Code.EMPTY_MESSAGE, "Chat message is invalid.", failure))
        }

        return permission(senderUuid).thenCompose { allowed ->
            if (!allowed) return@thenCompose failed<ChatMessage>(ChatException(ChatException.Code.NO_PERMISSION, "You do not have permission to use network chat."))
            isMuted(senderUuid).thenCompose { muted ->
                if (muted) return@thenCompose failed<ChatMessage>(ChatException(ChatException.Code.MUTED, "You are muted and cannot chat."))
                if (!limiter.tryAcquire(senderUuid)) return@thenCompose failed<ChatMessage>(ChatException(ChatException.Code.RATE_LIMITED, "You are sending messages too quickly."))
                broadcast(chatMessage).thenApply { chatMessage }
            }
        }.exceptionallyCompose { failure ->
            val cause = unwrap(failure)
            if (cause is ChatException) failed(cause)
            else failed(ChatException(ChatException.Code.PUBLISH_FAILED, "Network chat is temporarily unavailable. Your message was not sent.", cause))
        }
    }

    override fun broadcast(message: ChatMessage): CompletionStage<Unit> {
        try { validate(message) } catch (failure: Throwable) { return CompletableFuture.failedFuture(failure) }
        val event = NetworkChatMessage(
            EventMetadata(eventType = NetworkChatMessage.TYPE, timestamp = message.timestamp, sourceServerId = message.serverId),
            message,
        )
        return try {
            eventBus.publish(event).exceptionallyCompose { failure ->
                CompletableFuture.failedFuture(ChatException(ChatException.Code.PUBLISH_FAILED, "Network chat is temporarily unavailable. Your message was not sent.", unwrap(failure)))
            }
        } catch (failure: Throwable) {
            CompletableFuture.failedFuture(ChatException(ChatException.Code.PUBLISH_FAILED, "Network chat is temporarily unavailable. Your message was not sent.", failure))
        }
    }

    override fun validate(message: ChatMessage) {
        if (message.message.isBlank()) throw ChatException(ChatException.Code.EMPTY_MESSAGE, "Chat messages cannot be empty.")
        if (message.message.length > config.maxMessageLength) throw ChatException(ChatException.Code.MESSAGE_TOO_LONG, "Chat message is too long (maximum ${config.maxMessageLength} characters).")
    }

    override fun isAllowed(senderUuid: UUID): CompletionStage<Boolean> =
        permission(senderUuid).thenCompose { allowed ->
            if (!allowed) CompletableFuture.completedFuture(false)
            else isMuted(senderUuid).thenApply { !it }
        }

    private fun permission(senderUuid: UUID): CompletionStage<Boolean> = try {
        permissions.hasPermission(senderUuid, CHAT_USE_PERMISSION).exceptionallyCompose { failure ->
            failed(ChatException(ChatException.Code.PERMISSION_CHECK_FAILED, "Chat permissions could not be checked right now.", unwrap(failure)))
        }
    } catch (failure: Throwable) {
        failed(ChatException(ChatException.Code.PERMISSION_CHECK_FAILED, "Chat permissions could not be checked right now.", failure))
    }

    private fun isMuted(senderUuid: UUID): CompletionStage<Boolean> = try {
        moderation.isMuted(senderUuid).exceptionallyCompose { failure ->
            failed(ChatException(ChatException.Code.MODERATION_CHECK_FAILED, "Your moderation status could not be checked right now.", unwrap(failure)))
        }
    } catch (failure: Throwable) {
        failed(ChatException(ChatException.Code.MODERATION_CHECK_FAILED, "Your moderation status could not be checked right now.", failure))
    }

    private fun <T> failed(failure: Throwable): CompletionStage<T> = CompletableFuture.failedFuture(failure)
    private fun unwrap(failure: Throwable): Throwable = failure.cause ?: failure

    companion object { const val CHAT_USE_PERMISSION = "titansmp.chat.use" }
}

/** Fixed-window per-player limiter; inactive UUID entries are removed on subsequent attempts. */
class ChatRateLimiter(
    private val messagesPerWindow: Int,
    private val windowMs: Long,
    private val enabled: Boolean = true,
    private val clock: Clock = Clock.systemUTC(),
) {
    private data class Window(var startedAt: Long, var count: Int)
    private val windows = java.util.concurrent.ConcurrentHashMap<UUID, Window>()

    init { require(messagesPerWindow > 0 && windowMs > 0) }

    fun tryAcquire(playerId: UUID): Boolean {
        if (!enabled) return true
        val now = clock.millis()
        windows.entries.removeIf { now - it.value.startedAt >= windowMs * 2 }
        var allowed = false
        windows.compute(playerId) { _, current ->
            when {
                current == null || now - current.startedAt >= windowMs -> Window(now, 1).also { allowed = true }
                current.count < messagesPerWindow -> current.apply { count++; allowed = true }
                else -> current
            }
        }
        return allowed
    }
}
