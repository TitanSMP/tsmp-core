package de.titansmp.core.discord.chat

import de.titansmp.api.chat.ChatMessage
import de.titansmp.api.chat.ChatMessageSource
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class DiscordChatFormatterTest {
    private fun message(prefix: String) = ChatMessage(
        UUID.randomUUID(), UUID.randomUUID(), "Henry", "Hallo!", "smp-01", Instant.parse("2026-10-19T20:00:00Z"),
        ChatMessageSource.MINECRAFT, prefix,
    )

    @Test
    fun `renders the minecraft prefix and name`() {
        assertEquals("[MC] [Admin] Henry: Hallo!", DiscordChatFormatter.DEFAULT.render(message("[Admin]")))
    }

    @Test
    fun `omits an empty prefix`() {
        assertEquals("[MC] Henry: Hallo!", DiscordChatFormatter.DEFAULT.render(message("")))
    }
}
