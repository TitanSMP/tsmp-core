package de.titansmp.api.stats

import java.time.Instant
import java.util.UUID

/**
 * The tracked player statistics.
 *
 * This enum is the stable, extensible identity of a statistic. New statistics are appended; the
 * persisted column name is derived from [StatsService]'s stable mapping and never changes for an
 * existing value, so adding a statistic does not rewrite existing rows. Economy balance is
 * deliberately **not** a statistic; it is owned exclusively by the economy module.
 *
 * Units are part of the contract:
 * - [PLAYTIME] is stored in whole seconds.
 * - [BLOCKS_WALKED] and [BLOCKS_ELYTRA] are stored in whole blocks (distance).
 * - [DAMAGE_TAKEN] and [DAMAGE_DEALT] are stored in whole health points (1.0 = one heart half).
 * - [JOINS], [DEATHS], [BLOCKS_MINED], [MOBS_KILLED], and [PLAYERS_KILLED] are counts.
 */
enum class StatType {
    PLAYTIME,
    JOINS,
    DEATHS,
    BLOCKS_MINED,
    BLOCKS_WALKED,
    BLOCKS_ELYTRA,
    DAMAGE_TAKEN,
    DAMAGE_DEALT,
    MOBS_KILLED,
    PLAYERS_KILLED,
}

/**
 * An immutable, persisted snapshot of one player's statistics.
 *
 * Every value is a non-negative whole number and is monotonic: statistics only ever increase. The
 * totals are network-wide, so a player's statistics are identical on every server.
 */
data class PlayerStats(
    val playerUuid: UUID,
    val playtimeSeconds: Long,
    val joins: Long,
    val deaths: Long,
    val blocksMined: Long,
    val blocksWalked: Long,
    val blocksElytra: Long,
    val damageTaken: Long,
    val damageDealt: Long,
    val mobsKilled: Long,
    val playersKilled: Long,
    val updatedAt: Instant,
) {
    init {
        require(playtimeSeconds >= 0) { "Playtime must not be negative" }
        require(joins >= 0) { "Joins must not be negative" }
        require(deaths >= 0) { "Deaths must not be negative" }
        require(blocksMined >= 0) { "Blocks mined must not be negative" }
        require(blocksWalked >= 0) { "Blocks walked must not be negative" }
        require(blocksElytra >= 0) { "Blocks elytra must not be negative" }
        require(damageTaken >= 0) { "Damage taken must not be negative" }
        require(damageDealt >= 0) { "Damage dealt must not be negative" }
        require(mobsKilled >= 0) { "Mobs killed must not be negative" }
        require(playersKilled >= 0) { "Players killed must not be negative" }
    }

    /** The value of a single statistic; convenient for generic rendering and leaderboards. */
    fun valueOf(statType: StatType): Long = when (statType) {
        StatType.PLAYTIME -> playtimeSeconds
        StatType.JOINS -> joins
        StatType.DEATHS -> deaths
        StatType.BLOCKS_MINED -> blocksMined
        StatType.BLOCKS_WALKED -> blocksWalked
        StatType.BLOCKS_ELYTRA -> blocksElytra
        StatType.DAMAGE_TAKEN -> damageTaken
        StatType.DAMAGE_DEALT -> damageDealt
        StatType.MOBS_KILLED -> mobsKilled
        StatType.PLAYERS_KILLED -> playersKilled
    }

    /** Returns a copy with [statType] increased by [delta]. [delta] must not be negative. */
    fun increment(statType: StatType, delta: Long): PlayerStats {
        require(delta >= 0) { "A statistic can never be decremented" }
        if (delta == 0L) return this
        return when (statType) {
            StatType.PLAYTIME -> copy(playtimeSeconds = playtimeSeconds + delta)
            StatType.JOINS -> copy(joins = joins + delta)
            StatType.DEATHS -> copy(deaths = deaths + delta)
            StatType.BLOCKS_MINED -> copy(blocksMined = blocksMined + delta)
            StatType.BLOCKS_WALKED -> copy(blocksWalked = blocksWalked + delta)
            StatType.BLOCKS_ELYTRA -> copy(blocksElytra = blocksElytra + delta)
            StatType.DAMAGE_TAKEN -> copy(damageTaken = damageTaken + delta)
            StatType.DAMAGE_DEALT -> copy(damageDealt = damageDealt + delta)
            StatType.MOBS_KILLED -> copy(mobsKilled = mobsKilled + delta)
            StatType.PLAYERS_KILLED -> copy(playersKilled = playersKilled + delta)
        }
    }

    companion object {
        /** All-zero statistics for a player with no persisted row yet. */
        fun empty(playerUuid: UUID, at: Instant = Instant.EPOCH): PlayerStats = PlayerStats(
            playerUuid = playerUuid,
            playtimeSeconds = 0,
            joins = 0,
            deaths = 0,
            blocksMined = 0,
            blocksWalked = 0,
            blocksElytra = 0,
            damageTaken = 0,
            damageDealt = 0,
            mobsKilled = 0,
            playersKilled = 0,
            updatedAt = at,
        )
    }
}

/**
 * One row of a public statistics leaderboard.
 *
 * [rank] is one-based and awarded by the deterministic `ORDER BY value DESC, player_uuid ASC`
 * ordering. [username] is display metadata resolved from the shared identity store; it is never
 * used as identity.
 */
data class StatsLeaderboardEntry(
    val rank: Int,
    val statType: StatType,
    val playerUuid: UUID,
    val username: String,
    val value: Long,
) {
    init {
        require(rank >= 1) { "Leaderboard rank must be positive" }
        require(value >= 0) { "A statistic can never be negative" }
    }
}
