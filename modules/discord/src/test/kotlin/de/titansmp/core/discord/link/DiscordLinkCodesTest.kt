package de.titansmp.core.discord.link

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DiscordLinkCodesTest {
    @Test
    fun `generated codes use the unambiguous alphabet and have high entropy length`() {
        val code = DiscordLinkCodes.generate()
        assertEquals(DiscordLinkCodes.LENGTH, code.length)
        assertTrue(code.all { it in "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" }, "unexpected character in $code")
        assertTrue(DiscordLinkCodes.generate() != DiscordLinkCodes.generate(), "codes must not repeat")
    }

    @Test
    fun `display groups the code and normalize recovers the canonical form`() {
        val code = DiscordLinkCodes.generate()
        val display = DiscordLinkCodes.display(code)
        assertTrue(display.contains("-"))
        assertEquals(code, DiscordLinkCodes.normalize(display))
        assertEquals(code, DiscordLinkCodes.normalize(code.lowercase()))
        assertEquals(code, DiscordLinkCodes.normalize(" ${display.lowercase()} "))
    }

    @Test
    fun `hash is a stable sha256 hex that never contains the plaintext`() {
        val code = DiscordLinkCodes.generate()
        val hash = DiscordLinkCodes.hash(code)
        assertEquals(64, hash.length)
        assertTrue(hash.matches(Regex("[0-9a-f]{64}")))
        assertEquals(hash, DiscordLinkCodes.hash(DiscordLinkCodes.display(code)))
        assertNotEquals(code, hash)
        assertTrue(!hash.contains(code))
    }
}
