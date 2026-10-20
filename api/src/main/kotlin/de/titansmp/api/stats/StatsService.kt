package de.titansmp.api.stats

import java.util.UUID
import java.util.concurrent.CompletionStage

/**
 * The single, platform-independent boundary for network-wide player statistics.
 *
 * All mutations are **additive**. Statistics only ever increase, and every increment is applied with
 * an atomic SQL `value = value + ?` upsert so concurrent updates from different network servers
 * cannot lose data. Implementations persist through the shared MariaDB DataSource owned by `core`;
 * they must never perform blocking database work on a game server thread.
 *
 * The API is intentionally read/write and free of any platform, database, Redis, or permission
 * dependency so the future web API can call it directly.
 */
interface StatsService {
    /**
     * Atomically adds [delta] to a single statistic and returns the updated snapshot.
     *
     * [delta] must not be negative; a zero delta is a no-op that still returns the current snapshot.
     * A player without a persisted row is created with only this statistic set.
     */
    fun increment(playerUuid: UUID, statType: StatType, delta: Long): CompletionStage<PlayerStats>

    /**
     * Atomically adds every non-zero entry of [deltas] to one player's statistics in a single
     * transaction and returns the updated snapshot. Negative deltas are rejected.
     */
    fun incrementAll(playerUuid: UUID, deltas: Map<StatType, Long>): CompletionStage<PlayerStats>

    /** Returns the player's statistics; a player without a row yields [PlayerStats.empty]. */
    fun getStats(playerUuid: UUID): CompletionStage<PlayerStats>

    /** Returns only the requested statistics for deterministic, single-column reads. */
    fun getStats(playerUuid: UUID, statTypes: Collection<StatType>): CompletionStage<PlayerStats>

    /**
     * Returns the highest players for one statistic using a database-side
     * `ORDER BY value DESC, player_uuid ASC LIMIT`. [limit] is clamped to `1..MAX_TOP_LIMIT`.
     */
    fun getTopPlayers(statType: StatType, limit: Int = DEFAULT_TOP_LIMIT): CompletionStage<List<StatsLeaderboardEntry>>

    companion object {
        const val DEFAULT_TOP_LIMIT = 10
        const val MAX_TOP_LIMIT = 100
    }
}
