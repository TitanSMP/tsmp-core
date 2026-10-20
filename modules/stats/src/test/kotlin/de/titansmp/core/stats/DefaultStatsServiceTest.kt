package de.titansmp.core.stats

import de.titansmp.api.stats.PlayerStats
import de.titansmp.api.stats.PlayerStatsUpdated
import de.titansmp.api.stats.StatType
import de.titansmp.api.stats.StatsService
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Blocks until the stage completes, mirroring the synchronous assertions used by the other module tests. */
private fun <T> CompletionStage<T>.join(): T = toCompletableFuture().join()

class DefaultStatsServiceTest {
    private val fixedInstant = Instant.parse("2026-10-20T10:00:00Z")
    private val clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
    private val repository = InMemoryStatsRepository()
    private val eventBus = FakeEventBus()
    private val service = DefaultStatsService(
        repository = repository,
        eventBus = eventBus,
        executor = Executor(Runnable::run),
        serverId = "smp-01",
        clock = clock,
    )
    private val player = UUID.randomUUID()

    @Test
    fun `increment persists a single statistic and returns the snapshot`() {
        val stats = service.increment(player, StatType.BLOCKS_MINED, 5).join()

        assertEquals(5, stats.blocksMined)
        assertEquals(0, stats.joins)
        assertEquals(fixedInstant, stats.updatedAt)
        assertEquals(5, service.getStats(player).join().blocksMined)
    }

    @Test
    fun `incrementAll applies every delta and is additive`() {
        service.incrementAll(player, mapOf(StatType.BLOCKS_MINED to 3, StatType.MOBS_KILLED to 2)).join()
        val stats = service.incrementAll(player, mapOf(StatType.BLOCKS_MINED to 4)).join()

        assertEquals(7, stats.blocksMined)
        assertEquals(2, stats.mobsKilled)
    }

    @Test
    fun `concurrent increments never lose an update`() {
        val threads = 16
        val perThread = 50
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        try {
            val futures = (0 until threads).map {
                pool.submit {
                    repeat(perThread) { service.increment(player, StatType.BLOCKS_MINED, 1).join() }
                }
            }
            futures.forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }

        assertEquals((threads * perThread).toLong(), service.getStats(player).join().blocksMined)
    }

    @Test
    fun `negative deltas are rejected and never reduce a statistic`() {
        assertFailsWith<CompletionException> { service.increment(player, StatType.DEATHS, -1).join() }
        assertFailsWith<CompletionException> {
            service.incrementAll(player, mapOf(StatType.DEATHS to -5, StatType.JOINS to 1)).join()
        }
        assertFailsWith<CompletionException> { service.incrementAll(player, mapOf(StatType.DEATHS to 3, StatType.JOINS to -2)).join() }
        assertEquals(0, service.getStats(player).join().deaths)
        assertEquals(0, service.getStats(player).join().joins)
    }

    @Test
    fun `a player without a row reads an empty snapshot`() {
        val stats = service.getStats(UUID.randomUUID()).join()
        assertEquals(0, stats.playtimeSeconds)
        assertEquals(0, stats.joins)
        assertEquals(0, stats.blocksMined)
    }

    @Test
    fun `a filtered snapshot only returns the requested statistics`() {
        service.incrementAll(player, mapOf(StatType.BLOCKS_MINED to 9, StatType.DEATHS to 4, StatType.JOINS to 1)).join()

        val filtered = service.getStats(player, listOf(StatType.BLOCKS_MINED, StatType.JOINS)).join()

        assertEquals(9, filtered.blocksMined)
        assertEquals(1, filtered.joins)
        assertEquals(0, filtered.deaths)
    }

    @Test
    fun `leaderboard orders by value descending with a deterministic uuid tie breaker and limit`() {
        val low = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val high = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val richest = UUID.randomUUID()
        service.increment(low, StatType.BLOCKS_MINED, 100).join()
        service.increment(high, StatType.BLOCKS_MINED, 100).join()
        service.increment(richest, StatType.BLOCKS_MINED, 500).join()
        service.increment(UUID.randomUUID(), StatType.BLOCKS_MINED, 0).join()

        val top = service.getTopPlayers(StatType.BLOCKS_MINED, 10).join()

        assertEquals(listOf(richest, low, high), top.map { it.playerUuid })
        assertEquals(listOf(1, 2, 3), top.map { it.rank })
        assertEquals(listOf(500L, 100L, 100L), top.map { it.value })
        assertEquals(2, service.getTopPlayers(StatType.BLOCKS_MINED, 2).join().size)
    }

    @Test
    fun `leaderboard limit is clamped to the supported range`() {
        repeat(3) { service.increment(UUID.randomUUID(), StatType.JOINS, 1).join() }
        assertTrue(service.getTopPlayers(StatType.JOINS, 0).join().size <= 1)
        assertTrue(service.getTopPlayers(StatType.JOINS, Int.MAX_VALUE).join().size <= StatsService.MAX_TOP_LIMIT)
    }

    @Test
    fun `an aggregated update event is published with the absolute persisted totals`() {
        service.incrementAll(player, mapOf(StatType.BLOCKS_MINED to 2, StatType.JOINS to 1)).join()

        val events = eventBus.events.filterIsInstance<PlayerStatsUpdated>()
        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(player, event.playerUuid)
        assertEquals(2, event.stats.blocksMined)
        assertEquals(1, event.stats.joins)
        assertEquals(PlayerStatsUpdated.TYPE, event.metadata.eventType)
        assertEquals("smp-01", event.metadata.sourceServerId)
    }

    @Test
    fun `events can be disabled for isolated callers`() {
        val quiet = DefaultStatsService(repository, eventBus, Executor(Runnable::run), "smp-01", publishEvents = false, clock = clock)
        quiet.increment(UUID.randomUUID(), StatType.JOINS, 1).join()
        assertTrue(eventBus.events.isEmpty())
    }

    @Test
    fun `incrementing zero still returns the current snapshot`() {
        service.increment(player, StatType.JOINS, 3).join()
        val stats: PlayerStats = service.increment(player, StatType.JOINS, 0).join()
        assertEquals(3, stats.joins)
    }
}
