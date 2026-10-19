package de.titansmp.core.discord.command

import de.titansmp.core.config.DiscordLinkingConfig
import de.titansmp.core.discord.FakeRoleNotifier
import de.titansmp.core.discord.InMemoryDiscordLinkRepository
import de.titansmp.core.discord.MutableClock
import de.titansmp.core.discord.RecordingEventBus
import de.titansmp.core.discord.link.DefaultDiscordLinkService
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiscordLinkCommandHandlerTest {
    private val clock = MutableClock(Instant.parse("2026-10-19T20:00:00Z"))
    private val roles = FakeRoleNotifier()
    private val links = DefaultDiscordLinkService(
        InMemoryDiscordLinkRepository(), RecordingEventBus(), "discord", DiscordLinkingConfig(), Executor(Runnable::run), clock,
    )
    private val handler = DiscordLinkCommandHandler(links, roles)

    @Test
    fun `a valid code links the account and grants the verified role`() {
        val code = links.createLinkToken(UUID.randomUUID(), "Henry").toCompletableFuture().join()
        val result = handler.link(10L, code.code).toCompletableFuture().join()
        assertEquals("Your Discord account is now linked to your Minecraft account.", result.message)
        assertEquals(listOf(10L), roles.granted)
    }

    @Test
    fun `an invalid code produces the invalid-or-expired message without granting a role`() {
        val result = handler.link(10L, "ABCDE-FGHIJ").toCompletableFuture().join()
        assertEquals("This link code is invalid or has expired.", result.message)
        assertTrue(roles.granted.isEmpty())
    }

    @Test
    fun `a verified role failure keeps the link and warns the user`() {
        val code = links.createLinkToken(UUID.randomUUID(), "Henry").toCompletableFuture().join()
        roles.grantFailure = IllegalStateException("Discord unavailable")
        val result = handler.link(11L, code.code).toCompletableFuture().join()
        assertTrue(result.message.startsWith("Your account was linked, but the verified role"))
        // The link is durable even though the role could not be granted.
        assertEquals(11L, links.findByDiscord(11L).toCompletableFuture().join()?.discordUserId)
    }

    @Test
    fun `unlink removes the link and revokes the role`() {
        val code = links.createLinkToken(UUID.randomUUID(), "Henry").toCompletableFuture().join()
        links.link(12L, code.code).toCompletableFuture().join()
        val result = handler.unlink(12L).toCompletableFuture().join()
        assertEquals("Your account link has been removed.", result.message)
        assertEquals(listOf(12L), roles.revoked)
    }

    @Test
    fun `unlink reports when nothing is linked`() {
        val result = handler.unlink(13L).toCompletableFuture().join()
        assertEquals("Your Discord account is not linked to a Minecraft account.", result.message)
        assertTrue(roles.revoked.isEmpty())
    }

    @Test
    fun `rate limited attempts produce a throttling message`() {
        val limited = DiscordLinkCommandHandler(
            DefaultDiscordLinkService(
                InMemoryDiscordLinkRepository(), RecordingEventBus(), "discord",
                DiscordLinkingConfig(maxAttemptsPerWindow = 1, attemptWindowSeconds = 60), Executor(Runnable::run), clock,
            ),
            roles,
        )
        limited.link(14L, "ABCDE-FGHIJ").toCompletableFuture().join()
        val result = limited.link(14L, "ABCDE-FGHIJ").toCompletableFuture().join()
        assertEquals("Too many link attempts. Please wait a moment and try again.", result.message)
    }
}
