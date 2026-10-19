package de.titansmp.core.discord.link

import de.titansmp.api.discord.DiscordAccountUnlinked
import de.titansmp.api.discord.DiscordLinkException
import de.titansmp.core.config.DiscordLinkingConfig
import de.titansmp.core.discord.InMemoryDiscordLinkRepository
import de.titansmp.core.discord.MutableClock
import de.titansmp.core.discord.RecordingEventBus
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultDiscordLinkServiceTest {
    private val clock = MutableClock(Instant.parse("2026-10-19T20:00:00Z"))
    private val direct = Executor(Runnable::run)
    private val repository = InMemoryDiscordLinkRepository()
    private val bus = RecordingEventBus()
    private val linking = DiscordLinkingConfig(tokenExpirationMinutes = 10, maxAttemptsPerWindow = 5, attemptWindowSeconds = 60)

    private fun service(executor: Executor = direct, config: DiscordLinkingConfig = linking) =
        DefaultDiscordLinkService(repository, bus, "smp-01", config, executor, clock)

    private fun token(playerUuid: UUID = UUID.randomUUID(), name: String = "Henry") =
        service().createLinkToken(playerUuid, name).toCompletableFuture().join()

    @Test
    fun `a valid code links the player and stores only a hash`() {
        val player = UUID.randomUUID()
        val created = service().createLinkToken(player, "Henry").toCompletableFuture().join()
        val link = service().link(4242L, created.code).toCompletableFuture().join()

        assertEquals(player, link.playerUuid)
        assertEquals("Henry", link.playerName)
        assertEquals(4242L, link.discordUserId)

        val stored = repository.storedTokenHashes().single()
        assertEquals(DiscordLinkCodes.hash(created.code), stored)
        assertTrue(!stored.contains(created.code.replace("-", "")))
    }

    @Test
    fun `an invalid code is rejected`() {
        assertCode(DiscordLinkException.Code.INVALID_CODE) { service().link(1L, "ABCDE-FGHIJ") }
    }

    @Test
    fun `an expired code is rejected`() {
        val created = token()
        clock.advance(Duration.ofMinutes(11))
        assertCode(DiscordLinkException.Code.EXPIRED_CODE) { service().link(1L, created.code) }
    }

    @Test
    fun `a code can be redeemed only once`() {
        val created = token()
        service().link(1L, created.code).toCompletableFuture().join()
        assertCode(DiscordLinkException.Code.USED_CODE) { service().link(2L, created.code) }
    }

    @Test
    fun `a player that is already linked cannot create another token`() {
        val player = UUID.randomUUID()
        val created = service().createLinkToken(player, "Henry").toCompletableFuture().join()
        service().link(1L, created.code).toCompletableFuture().join()

        assertCode(DiscordLinkException.Code.ALREADY_LINKED_PLAYER) { service().createLinkToken(player, "Henry") }
    }

    @Test
    fun `a discord user that is already linked is rejected for a second player`() {
        val firstCode = service().createLinkToken(UUID.randomUUID(), "A").toCompletableFuture().join()
        service().link(99L, firstCode.code).toCompletableFuture().join()
        val secondCode = service().createLinkToken(UUID.randomUUID(), "B").toCompletableFuture().join()
        assertCode(DiscordLinkException.Code.ALREADY_LINKED_DISCORD) { service().link(99L, secondCode.code) }
    }

    @Test
    fun `creating a new token replaces the previous unused token`() {
        val player = UUID.randomUUID()
        val first = service().createLinkToken(player, "Henry").toCompletableFuture().join()
        val second = service().createLinkToken(player, "Henry").toCompletableFuture().join()
        assertEquals(1, repository.playerTokens(player))
        service().link(1L, second.code).toCompletableFuture().join()
        assertCode(DiscordLinkException.Code.INVALID_CODE) { service().link(2L, first.code) }
    }

    @Test
    fun `unlink by player removes the link and publishes the unlink event`() {
        val player = UUID.randomUUID()
        val created = service().createLinkToken(player, "Henry").toCompletableFuture().join()
        service().link(5L, created.code).toCompletableFuture().join()

        val removed = service().unlinkByPlayer(player).toCompletableFuture().join()
        assertEquals(5L, removed?.discordUserId)
        assertNull(service().findByPlayer(player).toCompletableFuture().join())
        val event = bus.events.filterIsInstance<DiscordAccountUnlinked>().single()
        assertEquals(player, event.playerUuid)
        assertEquals(5L, event.discordUserId)
    }

    @Test
    fun `unlink by discord removes the link and is null when unknown`() {
        val player = UUID.randomUUID()
        val created = service().createLinkToken(player, "Henry").toCompletableFuture().join()
        service().link(6L, created.code).toCompletableFuture().join()
        assertNotNull(service().unlinkByDiscord(6L).toCompletableFuture().join())
        assertNull(service().unlinkByDiscord(6L).toCompletableFuture().join())
        assertTrue(bus.events.filterIsInstance<DiscordAccountUnlinked>().size == 1)
    }

    @Test
    fun `the attempt limiter blocks repeated failures per discord user`() {
        val limited = service(config = DiscordLinkingConfig(maxAttemptsPerWindow = 2, attemptWindowSeconds = 60))
        assertCode(DiscordLinkException.Code.INVALID_CODE) { limited.link(7L, "AAAAA-AAAAA") }
        assertCode(DiscordLinkException.Code.INVALID_CODE) { limited.link(7L, "BBBBB-BBBBB") }
        assertCode(DiscordLinkException.Code.RATE_LIMITED) { limited.link(7L, "CCCCC-CCCCC") }
    }

    @Test
    fun `concurrent redemption of one code links exactly one discord user`() {
        val executor = Executors.newFixedThreadPool(6)
        try {
            val concurrent = service(executor)
            val created = concurrent.createLinkToken(UUID.randomUUID(), "Henry").toCompletableFuture().join()
            val succeeded = java.util.concurrent.atomic.AtomicInteger()
            val messages = java.util.Collections.synchronizedList(mutableListOf<String>())
            val futures = (1..6).map { index ->
                concurrent.link(index.toLong(), created.code).handle { _, failure ->
                    if (failure == null) succeeded.incrementAndGet()
                    else messages += generateSequence(failure) { it.cause }.joinToString(" <- ") { it.toString() }
                    null
                }
            }
            futures.forEach { it.toCompletableFuture().join() }
            assertEquals(1, succeeded.get(), "exactly one of six concurrent redemptions may succeed; failures=$messages")
        } finally {
            executor.shutdownNow()
        }
    }

    private fun assertCode(expected: DiscordLinkException.Code, block: () -> java.util.concurrent.CompletionStage<*>) {
        val failure = assertFailsWith<CompletionException> { block().toCompletableFuture().join() }
        assertEquals(expected, (failure.cause as DiscordLinkException).code)
    }
}
