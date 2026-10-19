package de.titansmp.core.discord

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards the deployment rule: Discord IDs are configuration values and are never hardcoded in source.
 *
 * The current production guild, channel, and role IDs must only ever appear in the deployment config
 * file or in environment variables. This test fails if any of them leak into source or resources of the
 * Discord module, the bot runtime, or the Paper adapter.
 */
class DiscordConfigurationGuardTest {
    private val forbiddenIds = listOf(
        "1561860920743829594", // current Minecraft chat channel
        "1561861022414012556", // current verified role
    )

    private val roots = listOf(
        Path.of("src", "main"),
        Path.of("..", "..", "services", "discord-bot", "src", "main"),
        Path.of("..", "..", "paper", "src", "main"),
    )

    @Test
    fun `discord ids are never hardcoded in source`() {
        val violations = mutableListOf<String>()
        roots.filter { Files.isDirectory(it) }.forEach { root ->
            Files.walk(root).use { stream ->
                stream.filter { Files.isRegularFile(it) }.forEach { file ->
                    val text = String(Files.readAllBytes(file), Charsets.UTF_8)
                    forbiddenIds.filter(text::contains).forEach { id -> violations += "$file -> $id" }
                }
            }
        }
        assertTrue(violations.isEmpty(), "Discord IDs must only be configuration values: $violations")
    }
}
