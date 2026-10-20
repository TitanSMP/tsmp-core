package de.titansmp.core.stats

import de.titansmp.api.stats.StatType
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaytimeTrackerTest {
    private val repository = InMemoryStatsRepository()
    private val service = DefaultStatsService(
        repository,
        FakeEventBus(),
        Executor(Runnable::run),
        "smp-01",
        publishEvents = false,
        clock = java.time.Clock.fixed(Instant.parse("2026-10-20T10:00:00Z"), ZoneOffset.UTC),
    )
    private val aggregator = StatsAggregator(service)
    private val player = UUID.randomUUID()

    @Test
    fun `time is counted on an enabled server and flushed`() {
        val clock = MutableClock()
        val tracker = PlaytimeTracker(enabled = true, aggregator = aggregator, clock = clock)

        tracker.start(player)
        clock.advance(Duration.ofSeconds(120))
        tracker.stop(player)
        aggregator.flush()

        assertEquals(120, repository.find(player)!!.playtimeSeconds)
    }

    @Test
    fun `time is not counted on a disabled server`() {
        val clock = MutableClock()
        val tracker = PlaytimeTracker(enabled = false, aggregator = aggregator, clock = clock)

        tracker.start(player)
        clock.advance(Duration.ofSeconds(300))
        tracker.stop(player)
        aggregator.flush()

        assertEquals(null, repository.find(player))
    }

    @Test
    fun `an smp to lobby to smp journey counts only the enabled segments without double counting`() {
        val clock = MutableClock()
        val smp = PlaytimeTracker(enabled = true, aggregator = aggregator, clock = clock)
        val lobby = PlaytimeTracker(enabled = false, aggregator = aggregator, clock = clock)

        // SMP session: 60 seconds counted.
        smp.start(player)
        clock.advance(Duration.ofSeconds(60))
        smp.stop(player)

        // Lobby session: 30 seconds not counted.
        lobby.start(player)
        clock.advance(Duration.ofSeconds(30))
        lobby.stop(player)

        // Back on SMP: another 90 seconds counted.
        smp.start(player)
        clock.advance(Duration.ofSeconds(90))
        smp.stop(player)

        aggregator.flush()

        // 60 + 90 = 150 seconds; the 30 lobby seconds are excluded and nothing is counted twice.
        assertEquals(150, repository.find(player)!!.playtimeSeconds)
    }

    @Test
    fun `sub-second remainder is not rounded up`() {
        val clock = MutableClock()
        val tracker = PlaytimeTracker(enabled = true, aggregator = aggregator, clock = clock)

        tracker.start(player)
        clock.advance(Duration.ofMillis(1_500))
        tracker.stop(player)
        aggregator.flush()

        assertEquals(1, repository.find(player)!!.playtimeSeconds)
    }

    @Test
    fun `stop is idempotent and does not double count`() {
        val clock = MutableClock()
        val tracker = PlaytimeTracker(enabled = true, aggregator = aggregator, clock = clock)

        tracker.start(player)
        clock.advance(Duration.ofSeconds(45))
        tracker.stop(player)
        tracker.stop(player)
        aggregator.flush()

        assertEquals(45, repository.find(player)!!.playtimeSeconds)
    }

    @Test
    fun `stopAll accounts for open sessions on shutdown`() {
        val clock = MutableClock()
        val tracker = PlaytimeTracker(enabled = true, aggregator = aggregator, clock = clock)
        val second = UUID.randomUUID()

        tracker.start(player)
        tracker.start(second)
        clock.advance(Duration.ofSeconds(30))
        tracker.stopAll()
        aggregator.flush()

        assertEquals(30, repository.find(player)!!.playtimeSeconds)
        assertEquals(30, repository.find(second)!!.playtimeSeconds)
        assertEquals(0, tracker.openSessions())
    }

    @Test
    fun `a repeated join keeps the earliest start`() {
        val clock = MutableClock()
        val tracker = PlaytimeTracker(enabled = true, aggregator = aggregator, clock = clock)

        tracker.start(player)
        clock.advance(Duration.ofSeconds(20))
        tracker.start(player)
        clock.advance(Duration.ofSeconds(40))
        tracker.stop(player)
        aggregator.flush()

        assertEquals(60, repository.find(player)!!.playtimeSeconds)
    }

    @Test
    fun `the enabled flag matches a server type policy`() {
        val policy = StatsTrackingPolicy(
            de.titansmp.core.config.StatsConfig(
                enabled = true,
                tracking = de.titansmp.core.config.StatsConfig.defaultTracking() +
                    (StatType.PLAYTIME to de.titansmp.core.config.StatTrackingConfig(true, de.titansmp.core.config.StatScope.SERVER_TYPES, setOf("smp"))),
            ),
            serverIdentityType = "smp",
        )
        assertEquals(true, policy.tracks(StatType.PLAYTIME))
    }
}
