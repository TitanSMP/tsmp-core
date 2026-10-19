package de.titansmp.core.player

import de.titansmp.api.player.NetworkPlayer
import java.nio.ByteBuffer
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/** JDBC persistence against the shared core DataSource; timestamps are UTC DATETIME values. */
class JdbcPlayerRepository(private val dataSource: DataSource) : PlayerRepository {
    override fun findByUuid(uuid: UUID): NetworkPlayer? = queryOne("SELECT $COLUMNS FROM players WHERE uuid = ?") {
        setBytes(1, uuid.toBytes())
    }

    override fun findByUsername(username: String): NetworkPlayer? = queryOne(
        "SELECT $COLUMNS FROM players WHERE username = ? ORDER BY last_seen DESC LIMIT 1",
    ) { setString(1, username) }

    override fun create(uuid: UUID, username: String, now: Instant): NetworkPlayer {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO players (uuid, username, first_join, last_seen, current_server, online) VALUES (?, ?, ?, ?, NULL, FALSE)",
            ).use {
                it.setBytes(1, uuid.toBytes())
                it.setString(2, username)
                it.setUtc(3, now)
                it.setUtc(4, now)
                it.executeUpdate()
            }
        }
        return requireNotNull(findByUuid(uuid))
    }

    override fun createOrUpdateIdentity(uuid: UUID, username: String, now: Instant): NetworkPlayer {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO players (uuid, username, first_join, last_seen, current_server, online) VALUES (?, ?, ?, ?, NULL, FALSE) " +
                    "ON DUPLICATE KEY UPDATE username = VALUES(username), last_seen = VALUES(last_seen)",
            ).use {
                it.setBytes(1, uuid.toBytes())
                it.setString(2, username)
                it.setUtc(3, now)
                it.setUtc(4, now)
                it.executeUpdate()
            }
        }
        return requireNotNull(findByUuid(uuid))
    }

    override fun updateUsername(uuid: UUID, username: String): Boolean = update(
        "UPDATE players SET username = ? WHERE uuid = ?",
    ) { setString(1, username); setBytes(2, uuid.toBytes()) } > 0

    override fun updateLastSeen(uuid: UUID, now: Instant): Boolean = update(
        "UPDATE players SET last_seen = ? WHERE uuid = ?",
    ) { setUtc(1, now); setBytes(2, uuid.toBytes()) } > 0

    override fun setOnline(uuid: UUID, serverId: String, now: Instant): Boolean = update(
        "UPDATE players SET online = TRUE, current_server = ?, last_seen = ? WHERE uuid = ?",
    ) { setString(1, serverId); setUtc(2, now); setBytes(3, uuid.toBytes()) } > 0

    override fun setOffline(uuid: UUID, now: Instant): Boolean = update(
        "UPDATE players SET online = FALSE, current_server = NULL, last_seen = ? WHERE uuid = ?",
    ) { setUtc(1, now); setBytes(2, uuid.toBytes()) } > 0

    override fun setOffline(uuid: UUID, expectedServerId: String, now: Instant): Boolean = update(
        "UPDATE players SET online = FALSE, current_server = NULL, last_seen = ? WHERE uuid = ? AND current_server = ?",
    ) { setUtc(1, now); setBytes(2, uuid.toBytes()); setString(3, expectedServerId) } > 0

    private fun update(sql: String, bind: PreparedStatement.() -> Unit): Int = dataSource.connection.use { connection ->
        connection.prepareStatement(sql).use { statement -> statement.bind(); statement.executeUpdate() }
    }

    private fun queryOne(sql: String, bind: PreparedStatement.() -> Unit): NetworkPlayer? =
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.bind()
                statement.executeQuery().use { result -> if (result.next()) result.toPlayer() else null }
            }
        }

    private fun PreparedStatement.setUtc(index: Int, instant: Instant) =
        setObject(index, LocalDateTime.ofInstant(instant, ZoneOffset.UTC))

    private fun ResultSet.toPlayer(): NetworkPlayer = NetworkPlayer(
        uuid = getBytes("uuid").toUuid(),
        username = getString("username"),
        firstJoin = getObject("first_join", LocalDateTime::class.java).toInstant(ZoneOffset.UTC),
        lastSeen = getObject("last_seen", LocalDateTime::class.java).toInstant(ZoneOffset.UTC),
        currentServer = getString("current_server"),
        online = getBoolean("online"),
    )

    private fun UUID.toBytes(): ByteArray = ByteBuffer.allocate(16).putLong(mostSignificantBits).putLong(leastSignificantBits).array()
    private fun ByteArray.toUuid(): UUID = ByteBuffer.wrap(this).let { UUID(it.long, it.long) }

    private companion object {
        const val COLUMNS = "uuid, username, first_join, last_seen, current_server, online"
    }
}
