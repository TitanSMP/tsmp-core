package de.titansmp.core.stats

import de.titansmp.api.stats.PlayerStats
import de.titansmp.api.stats.StatType
import de.titansmp.api.stats.StatsLeaderboardEntry
import java.time.Instant
import java.util.UUID

/**
 * Additive, race-safe statistics storage.
 *
 * Implementations must apply every increment with an atomic SQL `value = value + ?` upsert so that
 * concurrent updates from different network servers cannot lose data. A read-then-write pattern is
 * forbidden. Implementations run off the game-server thread.
 */
interface StatsRepository {
    /** Atomically adds [delta] to one statistic and returns the updated snapshot. */
    fun increment(playerUuid: UUID, statType: StatType, delta: Long, at: Instant): PlayerStats

    /**
     * Atomically adds every delta in one transaction and returns the updated snapshot. An empty
     * [deltas] map still returns the current snapshot, creating the row only when it already exists.
     */
    fun incrementAll(playerUuid: UUID, deltas: Map<StatType, Long>, at: Instant): PlayerStats

    /** Returns the persisted snapshot, or `null` when the player has no row. */
    fun find(playerUuid: UUID): PlayerStats?

    /** Returns the top players for [statType] ordered by `value DESC, player_uuid ASC`. */
    fun top(statType: StatType, limit: Int): List<StatsLeaderboardEntry>
}

/**
 * Maps the stable [StatType] identity to its persisted column name.
 *
 * The mapping is explicit so a renamed enum constant can never silently change a column, and so
 * adding a statistic appends a column instead of rewriting existing data.
 */
object StatColumns {
    private val columns: Map<StatType, String> = mapOf(
        StatType.PLAYTIME to "playtime",
        StatType.JOINS to "joins",
        StatType.DEATHS to "deaths",
        StatType.BLOCKS_MINED to "blocks_mined",
        StatType.BLOCKS_WALKED to "blocks_walked",
        StatType.BLOCKS_ELYTRA to "blocks_elytra",
        StatType.DAMAGE_TAKEN to "damage_taken",
        StatType.DAMAGE_DEALT to "damage_dealt",
        StatType.MOBS_KILLED to "mobs_killed",
        StatType.PLAYERS_KILLED to "players_killed",
    )

    fun columnFor(statType: StatType): String =
        columns[statType] ?: throw IllegalArgumentException("No persisted column for $statType")

    fun columnOrNull(column: String): StatType? = columns.entries.firstOrNull { it.value == column }?.key

    /** Column names in a stable order for deterministic SQL generation. */
    val allColumns: List<String> = columns.values.toList()
}
