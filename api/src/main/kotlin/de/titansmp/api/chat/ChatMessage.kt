package de.titansmp.api.chat

import java.time.Instant
import java.util.UUID

enum class ChatMessageSource { MINECRAFT, DISCORD, WEB, SYSTEM }

/** Platform-independent chat payload. Player identities are UUIDs; non-player sources may omit one. */
data class ChatMessage(
    val messageId: UUID,
    val senderUuid: UUID?,
    val senderName: String,
    val message: String,
    val serverId: String,
    val timestamp: Instant,
    val source: ChatMessageSource,
) {
    init {
        require(senderName.isNotBlank()) { "Chat sender name must not be blank" }
        require(serverId.isNotBlank()) { "Chat server ID must not be blank" }
        require(source != ChatMessageSource.MINECRAFT || senderUuid != null) { "Minecraft chat requires a player UUID" }
    }
}

/** Human-readable text only, leaving platform components and formatting to the adapter. */
fun interface ChatRenderer {
    fun render(message: ChatMessage): String
}

interface ChatService {
    fun send(senderUuid: UUID, senderName: String, message: String, serverId: String): java.util.concurrent.CompletionStage<ChatMessage>
    fun broadcast(message: ChatMessage): java.util.concurrent.CompletionStage<Unit>
    fun validate(message: ChatMessage)
    fun isAllowed(senderUuid: UUID): java.util.concurrent.CompletionStage<Boolean>
}

class ChatException(val code: Code, message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    enum class Code { EMPTY_MESSAGE, MESSAGE_TOO_LONG, NO_PERMISSION, PERMISSION_CHECK_FAILED, MODERATION_CHECK_FAILED, MUTED, RATE_LIMITED, PUBLISH_FAILED }
}

data class NetworkChatMessage(
    override val metadata: de.titansmp.api.event.EventMetadata,
    val message: ChatMessage,
) : de.titansmp.api.event.NetworkEvent {
    companion object { val TYPE = de.titansmp.api.event.EventType("chat.message") }
}
