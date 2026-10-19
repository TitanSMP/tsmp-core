package de.titansmp.core.discord.chat

import de.titansmp.api.chat.ChatMessage

/**
 * Turns an outgoing Minecraft [ChatMessage] into the plain-text line posted to Discord.
 *
 * This is the single place the Minecraft-to-Discord format is defined, so business logic never builds
 * Discord text by hand. v1 uses plain text only (no embeds). The prefix is the value already resolved
 * by the shared `PlayerDisplayService`; the formatter never looks up ranks itself.
 */
fun interface DiscordChatFormatter {
    fun render(message: ChatMessage): String

    companion object {
        /** `[MC] [Admin] Henry: Hello!` */
        val DEFAULT = DiscordChatFormatter { message ->
            val name = if (message.prefix.isBlank()) message.senderName else "${message.prefix} ${message.senderName}"
            "[MC] $name: ${message.message}"
        }
    }
}
