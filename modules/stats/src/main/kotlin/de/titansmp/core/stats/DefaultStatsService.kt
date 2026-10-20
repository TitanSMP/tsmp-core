package de.titansmp.core.stats

import de.titansmp.api.event.EventBus
import de.titansmp.api.event.EventMetadata
import de.titansmp.api.stats.PlayerStats
import de.titansmp.api.stats.StatType
import de.titansmp.api.stats.StatsLeaderboardEntry
import de.titansmp.api.stats.StatsService
import de.titansmp.api.stats.PlayerStatsUpdated
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Shared statistics business logic.
 *
 * MariaDB is the source of truth: every increment is an atomic additive upsert, and only after the
 * write committed is an aggregated [PlayerStatsUpdated] event published. Statistics are monotonic;
 * a negative delta is rejected before it reaches the database, so no statistic can ever decrease.
 *
 * The only intended high-frequency caller is the local Paper aggregator, which batches counters and
 * flushes them here periodically. That keeps both MariaDB writes and Redis events aggregated rather
 * than per block, step, or damage tick.
 */
class DefaultStatsService(
    private val repository: StatsRepository,
    private val eventBus: EventBus,
    private val executor: Executor,
    private val serverId: String,
    private val publishEvents: Boolean = true,
    private val clock: Clock = Clock.systemUTC(),
    private val logger: Logger = Logger.getLogger(DefaultStatsService::class.java.name),
) : StatsService {
    override fun increment(playerUuid: UUID, statType: StatType, delta: Long): CompletionStage<PlayerStats> {
        if (delta < 0) return failed(IllegalArgumentException("A statistic can never be decremented"))
        return incrementAll(playerUuid, mapOf(statType to delta))
    }

    override fun incrementAll(playerUuid: UUID, deltas: Map<StatType, Long>): CompletionStage<PlayerStats> {
        if (deltas.values.any { it < 0 }) return failed(IllegalArgumentException("A statistic can never be decremented"))
        val at = clock.instant()
        return db { repository.incrementAll(playerUuid, deltas, at) }.thenCompose { stats ->
            if (!publishEvents) CompletableFuture.completedFuture(stats)
            else publish(stats).thenApply { stats }
        }
    }

    override fun getStats(playerUuid: UUID): CompletionStage<PlayerStats> =
        db { repository.find(playerUuid) ?: PlayerStats.empty(playerUuid) }

    override fun getStats(playerUuid: UUID, statTypes: Collection<StatType>): CompletionStage<PlayerStats> {
        val requested = statTypes.toSet()
        return getStats(playerUuid).thenApply { stats ->
            if (requested.containsAll(StatType.entries.toSet())) return@thenApply stats
            StatType.entries.fold(PlayerStats.empty(playerUuid, stats.updatedAt)) { filtered, statType ->
                if (statType in requested) filtered.increment(statType, stats.valueOf(statType)) else filtered
            }
        }
    }

    override fun getTopPlayers(statType: StatType, limit: Int): CompletionStage<List<StatsLeaderboardEntry>> {
        val safeLimit = limit.coerceIn(1, StatsService.MAX_TOP_LIMIT)
        return db { repository.top(statType, safeLimit) }
    }

    private fun publish(stats: PlayerStats): CompletionStage<Unit> {
        val event = PlayerStatsUpdated(
            metadata = EventMetadata(
                eventType = PlayerStatsUpdated.TYPE,
                timestamp = clock.instant(),
                sourceServerId = serverId,
            ),
            playerUuid = stats.playerUuid,
            stats = stats,
        )
        return try {
            eventBus.publish(event).handle { _, failure ->
                if (failure != null) {
                    logger.log(
                        Level.SEVERE,
                        "Statistics for ${stats.playerUuid} were committed but Redis event ${PlayerStatsUpdated.TYPE.value} could not be published",
                        failure,
                    )
                }
                Unit
            }
        } catch (failure: Throwable) {
            logger.log(
                Level.SEVERE,
                "Statistics for ${stats.playerUuid} were committed but Redis event ${PlayerStatsUpdated.TYPE.value} could not be published",
                failure,
            )
            CompletableFuture.completedFuture(Unit)
        }
    }

    private fun <T> db(block: () -> T): CompletionStage<T> =
        CompletableFuture.supplyAsync(block, executor).exceptionallyCompose { failed(unwrap(it)) }

    private fun <T> failed(failure: Throwable): CompletionStage<T> = CompletableFuture.failedFuture(failure)

    private fun unwrap(failure: Throwable): Throwable {
        var current = failure
        while ((current is java.util.concurrent.CompletionException || current is java.util.concurrent.ExecutionException) && current.cause != null) {
            current = current.cause!!
        }
        return current
    }
}
