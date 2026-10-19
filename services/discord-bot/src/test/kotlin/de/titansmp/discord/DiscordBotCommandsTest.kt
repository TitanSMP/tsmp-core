package de.titansmp.discord

import net.dv8tion.jda.api.interactions.commands.OptionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Guards the Discord command surface of v1: nothing beyond /link and /unlink. */
class DiscordBotCommandsTest {
    @Test
    fun `only link and unlink are offered`() {
        val commands = DiscordBotCommands.definitions()
        assertEquals(listOf(DiscordBotCommands.LINK, DiscordBotCommands.UNLINK), commands.map { it.name })
        assertTrue(commands.all { it.description.isNotBlank() }, "every command needs a description")
    }

    @Test
    fun `link requires exactly one code option`() {
        val link = DiscordBotCommands.definitions().single { it.name == DiscordBotCommands.LINK }
        val options = link.options
        assertEquals(1, options.size)
        assertEquals(DiscordBotCommands.LINK_CODE_OPTION, options[0].name)
        assertEquals(OptionType.STRING, options[0].type)
        assertTrue(options[0].isRequired, "the link code must be required")
    }

    @Test
    fun `unlink takes no arguments`() {
        val unlink = DiscordBotCommands.definitions().single { it.name == DiscordBotCommands.UNLINK }
        assertTrue(unlink.options.isEmpty())
    }
}
