package de.titansmp.core.stats

import de.titansmp.api.stats.PlayerStats
import de.titansmp.api.stats.StatType
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatsAggregatorTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-20T10:00:00Z"), ZoneOffset.UTC)
    private val repository = InMemoryStatsRepository()
    private val service = DefaultStatsService(repository, FakeEventBus(), Executor(Runnable::run), "smp-01", publishEvents = false, clock = clock)
    private val aggregator = StatsAggregator(service)
    private val player = UUID.randomUUID()

    @Test
    fun `record accumulates locally without writing to the database`() {
        aggregator.record(player, StatType.BLOCKS_MINED, 1)
        aggregator.record(player, StatType.BLOCKS_MINED, 2)

        assertEquals(3, aggregator.pendingValue(player, StatType.BLOCKS_MINED))
        assertEquals(null, repository.find(player))
    }

    @Test
    fun `flush persists accumulated counters once`() {
        aggregator.record(player, StatType.BLOCKS_MINED, 5)
        aggregator.record(player, StatType.MOBS_KILLED, 2)

        aggregator.flush()

        val stats: PlayerStats = requireNotNull(repository.find(player))
        assertEquals(5, stats.blocksMined)
        assertEquals(2, stats.mobsKilled)
        assertEquals(0, aggregator.pendingValue(player, StatType.BLOCKS_MINED))
    }

    @Test
    fun `non-positive deltas are ignored`() {
        aggregator.record(player, StatType.DEATHS, 0)
        aggregator.record(player, StatType.DEATHS, -5)
        assertEquals(0, aggregator.pendingPlayers())
    }

    @Test
    fun `a flush with no pending counters is a no-op`() {
        aggregator.flush()
        assertTrue(repository.find(player) == null)
    }

    @Test
    fun `counters added after a flush go into the next flush`() {
        aggregator.record(player, StatType.BLOCKS_MINED, 3)
        aggregator.flush()
        aggregator.record(player, StatType.BLOCKS_MINED, 4)
        aggregator.flush()

        assertEquals(7, repository.find(player)!!.blocksMined)
    }
}
