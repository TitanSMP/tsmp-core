package de.titansmp.core.stats

import de.titansmp.api.stats.PlayerStats
import de.titansmp.api.stats.StatType
import de.titansmp.api.stats.StatsLeaderboardEntry
import java.nio.ByteBuffer
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/**
 * Additive JDBC adapter over the shared TitanSMP DataSource.
 *
 * Every increment is a single atomic upsert:
 *
 * ```sql
 * INSERT INTO player_stats (player_uuid, <cols>, created_at, updated_at)
 * VALUES (?, <delta>, ?, ?)
 * ON DUPLICATE KEY UPDATE <col> = <col> + VALUES(<col>), ..., updated_at = VALUES(updated_at)
 * ```
 *
 * Because the addition happens inside MariaDB, concurrent increments from different servers can
 * never lose data. No `SELECT`/`UPDATE` pair is used, and no row lock is required.
 */
class JdbcStatsRepository(private val dataSource: DataSource) : StatsRepository {
    override fun increment(playerUuid: UUID, statType: StatType, delta: Long, at: Instant): PlayerStats {
        require(delta >= 0) { "A statistic can never be decremented" }
        return incrementAll(playerUuid, mapOf(statType to delta), at)
    }

    override fun incrementAll(playerUuid: UUID, deltas: Map<StatType, Long>, at: Instant): PlayerStats {
        require(deltas.values.all { it >= 0 }) { "A statistic can never be decremented" }
        val nonZero = deltas.filterValues { it != 0L }
        if (nonZero.isEmpty()) return find(playerUuid) ?: emptySnapshot(playerUuid, at)
        val ordered = StatType.entries.filter { nonZero.containsKey(it) }
        return dataSource.connection.use { connection ->
            ensureRow(connection, playerUuid, ordered, nonZero, at)
            requireNotNull(read(connection, playerUuid)) { "The stats row disappeared after it was written" }
        }
    }

    override fun find(playerUuid: UUID): PlayerStats? = dataSource.connection.use { connection -> read(connection, playerUuid) }

    override fun top(statType: StatType, limit: Int): List<StatsLeaderboardEntry> {
        require(limit >= 1) { "Top limit must be positive" }
        val column = StatColumns.columnFor(statType)
        return dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT s.player_uuid, s.$column AS stat_value, p.username FROM player_stats s " +
                    "JOIN players p ON p.uuid = s.player_uuid WHERE s.$column > 0 " +
                    "ORDER BY s.$column DESC, s.player_uuid ASC LIMIT ?",
            ).use { statement ->
                statement.setInt(1, limit)
                statement.executeQuery().use { result ->
                    buildList {
                        var rank = 1
                        while (result.next()) {
                            add(
                                StatsLeaderboardEntry(
                                    rank = rank++,
                                    statType = statType,
                                    playerUuid = result.getBytes("player_uuid").toUuid(),
                                    username = result.getString("username"),
                                    value = result.getLong("stat_value"),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun ensureRow(
        connection: Connection,
        playerUuid: UUID,
        ordered: List<StatType>,
        deltas: Map<StatType, Long>,
        at: Instant,
    ) {
        val columns = ordered.map(StatColumns::columnFor)
        val insertColumns = (listOf("player_uuid") + columns + listOf("created_at", "updated_at")).joinToString(", ")
        val placeholders = (List(1 + columns.size) { "?" } + listOf("?", "?")).joinToString(", ")
        val updates = columns.joinToString(", ") { "$it = $it + VALUES($it)" } + ", updated_at = VALUES(updated_at)"
        val sql = "INSERT INTO player_stats ($insertColumns) VALUES ($placeholders) " +
            "ON DUPLICATE KEY UPDATE $updates"
        connection.prepareStatement(sql).use { statement ->
            var index = 1
            statement.setBytes(index++, playerUuid.toBytes())
            ordered.forEach { statType -> statement.setLong(index++, deltas.getValue(statType)) }
            statement.setUtc(index++, at)
            statement.setUtc(index, at)
            statement.executeUpdate()
        }
    }

    private fun read(connection: Connection, playerUuid: UUID): PlayerStats? {
        val columns = StatColumns.allColumns.joinToString(", ")
        return connection.prepareStatement(
            "SELECT player_uuid, $columns, updated_at FROM player_stats WHERE player_uuid = ?",
        ).use { statement ->
            statement.setBytes(1, playerUuid.toBytes())
            statement.executeQuery().use { result -> if (result.next()) result.toStats() else null }
        }
    }

    private fun ResultSet.toStats(): PlayerStats = PlayerStats(
        playerUuid = getBytes("player_uuid").toUuid(),
        playtimeSeconds = getLong("playtime"),
        joins = getLong("joins"),
        deaths = getLong("deaths"),
        blocksMined = getLong("blocks_mined"),
        blocksWalked = getLong("blocks_walked"),
        blocksElytra = getLong("blocks_elytra"),
        damageTaken = getLong("damage_taken"),
        damageDealt = getLong("damage_dealt"),
        mobsKilled = getLong("mobs_killed"),
        playersKilled = getLong("players_killed"),
        updatedAt = getObject("updated_at", LocalDateTime::class.java).toInstant(ZoneOffset.UTC),
    )

    private fun PreparedStatement.setUtc(index: Int, instant: Instant) =
        setObject(index, LocalDateTime.ofInstant(instant, ZoneOffset.UTC))

    private fun UUID.toBytes(): ByteArray =
        ByteBuffer.allocate(16).putLong(mostSignificantBits).putLong(leastSignificantBits).array()

    private fun ByteArray.toUuid(): UUID = ByteBuffer.wrap(this).let { UUID(it.long, it.long) }

    private fun emptySnapshot(playerUuid: UUID, at: Instant): PlayerStats =
        PlayerStats.empty(playerUuid, at)
}
