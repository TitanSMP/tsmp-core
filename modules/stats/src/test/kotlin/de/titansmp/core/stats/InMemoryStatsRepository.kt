package de.titansmp.core.stats

import de.titansmp.api.stats.PlayerStats
import de.titansmp.api.stats.StatType
import de.titansmp.api.stats.StatsLeaderboardEntry
import java.time.Instant
import java.util.UUID

/**
 * Deterministic in-memory [StatsRepository] for tests. It applies the same additive semantics as the
 * JDBC repository, including the `value DESC, player_uuid ASC` leaderboard ordering.
 */
class InMemoryStatsRepository(private val usernames: Map<UUID, String> = emptyMap()) : StatsRepository {
    private val rows = LinkedHashMap<UUID, PlayerStats>()
    private val lock = Any()

    override fun increment(playerUuid: UUID, statType: StatType, delta: Long, at: Instant): PlayerStats =
        incrementAll(playerUuid, mapOf(statType to delta), at)

    override fun incrementAll(playerUuid: UUID, deltas: Map<StatType, Long>, at: Instant): PlayerStats {
        require(deltas.values.all { it >= 0 }) { "A statistic can never be decremented" }
        synchronized(lock) {
            var stats = rows[playerUuid] ?: PlayerStats.empty(playerUuid, at)
            deltas.forEach { (statType, delta) -> stats = stats.increment(statType, delta) }
            stats = stats.copy(updatedAt = at)
            rows[playerUuid] = stats
            return stats
        }
    }

    override fun find(playerUuid: UUID): PlayerStats? = synchronized(lock) { rows[playerUuid] }

    override fun top(statType: StatType, limit: Int): List<StatsLeaderboardEntry> = synchronized(lock) {
        rows.values
            .filter { it.valueOf(statType) > 0 }
            .sortedWith(compareByDescending<PlayerStats> { it.valueOf(statType) }.thenBy { it.playerUuid })
            .take(limit)
            .mapIndexed { index, stats ->
                StatsLeaderboardEntry(
                    rank = index + 1,
                    statType = statType,
                    playerUuid = stats.playerUuid,
                    username = usernames[stats.playerUuid] ?: stats.playerUuid.toString(),
                    value = stats.valueOf(statType),
                )
            }
    }
}
