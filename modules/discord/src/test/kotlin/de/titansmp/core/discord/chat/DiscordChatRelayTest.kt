package de.titansmp.core.discord.chat

import de.titansmp.api.chat.ChatMessage
import de.titansmp.api.chat.ChatMessageSource
import de.titansmp.core.config.DiscordLinkingConfig
import de.titansmp.core.discord.FakeChatService
import de.titansmp.core.discord.FakeDisplayService
import de.titansmp.core.discord.InMemoryDiscordLinkRepository
import de.titansmp.core.discord.MutableClock
import de.titansmp.core.discord.RecordingEventBus
import de.titansmp.core.discord.link.DefaultDiscordLinkService
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscordChatRelayTest {
    private val clock = MutableClock(Instant.parse("2026-10-19T20:00:00Z"))
    private val chat = FakeChatService()
    private val display = FakeDisplayService("[Admin]")
    private val repository = InMemoryDiscordLinkRepository()
    private val links = DefaultDiscordLinkService(
        repository, RecordingEventBus(), "discord", DiscordLinkingConfig(), Executor(Runnable::run), clock,
    )
    private val relay = DiscordChatRelay(chat, links, display, GUILD, CHANNEL, "discord", clock = clock)

    @Test
    fun `outbound renders minecraft messages and drops discord-source echoes`() {
        val minecraft = message(ChatMessageSource.MINECRAFT)
        assertEquals("[MC] [Admin] Henry: Hello", relay.toDiscord(minecraft))
        assertNull(relay.toDiscord(message(ChatMessageSource.DISCORD)))
        assertNull(relay.toDiscord(message(ChatMessageSource.WEB)))
        assertNull(relay.toDiscord(message(ChatMessageSource.SYSTEM)))
    }

    @Test
    fun `inbound eligibility requires the configured guild channel and a human author`() {
        assertTrue(relay.acceptsInbound(GUILD, CHANNEL, isBot = false))
        assertTrue(!relay.acceptsInbound(GUILD, CHANNEL, isBot = true), "bot messages must be ignored")
        assertTrue(!relay.acceptsInbound(GUILD, OTHER_CHANNEL, isBot = false), "other channels must be ignored")
        assertTrue(!relay.acceptsInbound(OTHER_GUILD, CHANNEL, isBot = false), "other guilds must be ignored")
    }

    @Test
    fun `an unverified discord user cannot forward to minecraft`() {
        val result = relay.relayInbound(1L, "Hello").toCompletableFuture().join()
        assertEquals(DiscordRelayResult.NotVerified, result)
        assertTrue(chat.broadcasts.isEmpty())
    }

    @Test
    fun `a verified discord user is forwarded with the linked identity and resolved prefix`() {
        val player = UUID.randomUUID()
        val code = links.createLinkToken(player, "Henry").toCompletableFuture().join()
        links.link(1L, code.code).toCompletableFuture().join()

        val result = relay.relayInbound(1L, "Hello from Discord").toCompletableFuture().join()
        assertEquals(DiscordRelayResult.Relayed, result)
        val broadcast = chat.broadcasts.single()
        assertEquals(player, broadcast.senderUuid)
        assertEquals("Henry", broadcast.senderName)
        assertEquals(ChatMessageSource.DISCORD, broadcast.source)
        assertEquals("[Admin]", broadcast.prefix)
        assertEquals("Hello from Discord", broadcast.message)
    }

    @Test
    fun `empty messages are ignored and long messages are truncated`() {
        val player = UUID.randomUUID()
        val code = links.createLinkToken(player, "Henry").toCompletableFuture().join()
        links.link(2L, code.code).toCompletableFuture().join()

        assertEquals(DiscordRelayResult.Ignored, relay.relayInbound(2L, "   ").toCompletableFuture().join())
        relay.relayInbound(2L, "x".repeat(400)).toCompletableFuture().join()
        assertEquals(256, chat.broadcasts.single().message.length)
    }

    @Test
    fun `a failed minecraft broadcast is reported as ignored`() {
        val player = UUID.randomUUID()
        val code = links.createLinkToken(player, "Henry").toCompletableFuture().join()
        links.link(3L, code.code).toCompletableFuture().join()
        chat.failure = IllegalStateException("Redis unavailable")
        assertEquals(DiscordRelayResult.Ignored, relay.relayInbound(3L, "Hi").toCompletableFuture().join())
    }

    @Test
    fun `works without a display service`() {
        val player = UUID.randomUUID()
        val code = links.createLinkToken(player, "Henry").toCompletableFuture().join()
        links.link(4L, code.code).toCompletableFuture().join()
        val bare = DiscordChatRelay(chat, links, null, GUILD, CHANNEL, "discord", clock = clock)
        assertEquals(DiscordRelayResult.Relayed, bare.relayInbound(4L, "No prefix").toCompletableFuture().join())
        assertEquals("", chat.broadcasts.last().prefix)
    }

    private fun message(source: ChatMessageSource) = ChatMessage(
        UUID.randomUUID(), UUID.randomUUID(), "Henry", "Hello", "smp-01", clock.instant(), source, "[Admin]",
    )

    private companion object {
        const val GUILD = 1_000L
        const val CHANNEL = 2_000L
        const val OTHER_GUILD = 3_000L
        const val OTHER_CHANNEL = 4_000L
    }
}
