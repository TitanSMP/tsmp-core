package de.titansmp.core.display

import de.titansmp.api.chat.ChatMessageSource
import de.titansmp.api.display.PlayerDisplayMeta
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerDisplayFormatTest {
    private val player = UUID.randomUUID()

    @Test
    fun `name tag is prefix plus name and never includes the clan suffix`() {
        assertEquals("[Admin] Henry", PlayerDisplayFormat.nameTag("[Admin]", "Henry"))
        assertEquals("[Admin] Henry", PlayerDisplayFormat.nameTag(PlayerDisplayMeta(player, "[Admin]", "[TITAN]", 100), "Henry"))
        assertEquals("Henry", PlayerDisplayFormat.nameTag(null as String?, "Henry"))
        assertEquals("Henry", PlayerDisplayFormat.nameTag("", "Henry"))
        assertEquals("Henry", PlayerDisplayFormat.nameTag("   ", "Henry"))
    }

    @Test
    fun `tab entry is prefix plus name plus clan suffix`() {
        assertEquals("[Admin] Henry", PlayerDisplayFormat.tabEntry("[Admin]", "Henry", null))
        assertEquals("[Admin] Henry [TITAN]", PlayerDisplayFormat.tabEntry("[Admin]", "Henry", "[TITAN]"))
        assertEquals("[Admin] Henry", PlayerDisplayFormat.tabEntry("[Admin]", "Henry", "   "))
        assertEquals("Henry [TITAN]", PlayerDisplayFormat.tabEntry(null, "Henry", "[TITAN]"))
        assertEquals("Henry", PlayerDisplayFormat.tabEntry(null, "Henry", null))
    }

    @Test
    fun `tab entry from meta uses both prefix and clan suffix`() {
        assertEquals(
            "[Moderator] Henry [TITAN]",
            PlayerDisplayFormat.tabEntry(PlayerDisplayMeta(player, "[Moderator]", "[TITAN]", 50), "Henry"),
        )
    }

    @Test
    fun `chat message is prefix plus name plus message and never the clan suffix`() {
        assertEquals("[Admin] Henry: Hello", PlayerDisplayFormat.chatMessage("[Admin]", "Henry", "Hello"))
        assertEquals(
            "[Admin] Henry: Hello",
            PlayerDisplayFormat.chatMessage(PlayerDisplayMeta(player, "[Admin]", "[TITAN]", 100).prefix, "Henry", "Hello"),
        )
        assertEquals("Henry: Hello", PlayerDisplayFormat.chatMessage(null, "Henry", "Hello"))
    }

    @Test
    fun `name tag prefix keeps a single trailing space only for a non-blank prefix`() {
        assertEquals("[Admin] ", PlayerDisplayFormat.nameTagPrefix("[Admin]"))
        assertEquals("", PlayerDisplayFormat.nameTagPrefix(null))
        assertEquals("", PlayerDisplayFormat.nameTagPrefix(""))
        assertEquals("", PlayerDisplayFormat.nameTagPrefix("   "))
    }

    @Test
    fun `source aware chat lines mark discord web and system but never minecraft`() {
        assertEquals("[Admin] Henry: Hello", PlayerDisplayFormat.chatMessage(ChatMessageSource.MINECRAFT, "[Admin]", "Henry", "Hello"))
        assertEquals("[Discord] [Admin] Henry: Hello", PlayerDisplayFormat.chatMessage(ChatMessageSource.DISCORD, "[Admin]", "Henry", "Hello"))
        assertEquals("[Discord] Henry: Hello", PlayerDisplayFormat.chatMessage(ChatMessageSource.DISCORD, null, "Henry", "Hello"))
        assertEquals("[Web] Henry: Hello", PlayerDisplayFormat.chatMessage(ChatMessageSource.WEB, "", "Henry", "Hello"))
        assertEquals("[System] Restarting", PlayerDisplayFormat.chatMessage(ChatMessageSource.SYSTEM, "[Admin]", "Henry", "Restarting"))
    }
}
