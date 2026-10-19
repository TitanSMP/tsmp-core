package de.titansmp.core.discord.link

import de.titansmp.api.discord.DiscordAccountLink
import java.nio.ByteBuffer
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLIntegrityConstraintViolationException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/**
 * JDBC adapter for Discord account links using the shared TitanSMP DataSource.
 *
 * Single-use redemption relies on one conditional `UPDATE ... WHERE used_at IS NULL AND expires_at > ?`
 * so exactly one caller can flip the token to used. The unique indexes on `player_uuid` and
 * `discord_user_id` are the final safety net against concurrent link creation; violations are mapped to
 * [LinkInsertOutcome] instead of surfacing as raw JDBC failures.
 */
class JdbcDiscordLinkRepository(private val dataSource: DataSource) : DiscordLinkRepository {
    override fun findLinkByPlayer(playerUuid: UUID): DiscordAccountLink? = dataSource.connection.use { connection ->
        connection.prepareStatement("$SELECT_LINK WHERE player_uuid=?").use { statement ->
            statement.setBytes(1, playerUuid.toBytes())
            statement.executeQuery().use { result -> if (result.next()) result.toLink() else null }
        }
    }

    override fun findLinkByDiscord(discordUserId: Long): DiscordAccountLink? = dataSource.connection.use { connection ->
        connection.prepareStatement("$SELECT_LINK WHERE discord_user_id=?").use { statement ->
            statement.setLong(1, discordUserId)
            statement.executeQuery().use { result -> if (result.next()) result.toLink() else null }
        }
    }

    override fun replaceLinkToken(tokenHash: String, playerUuid: UUID, playerName: String, createdAt: Instant, expiresAt: Instant) {
        transaction { connection ->
            connection.prepareStatement("DELETE FROM discord_link_tokens WHERE player_uuid=? AND used_at IS NULL").use { statement ->
                statement.setBytes(1, playerUuid.toBytes())
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO discord_link_tokens (token_hash, player_uuid, player_name, created_at, expires_at, used_at) VALUES (?, ?, ?, ?, ?, NULL)",
            ).use { statement ->
                statement.setString(1, tokenHash)
                statement.setBytes(2, playerUuid.toBytes())
                statement.setString(3, playerName)
                statement.setUtc(4, createdAt)
                statement.setUtc(5, expiresAt)
                statement.executeUpdate()
            }
        }
    }

    override fun consumeToken(tokenHash: String, now: Instant): LinkTokenConsumption = dataSource.connection.use { connection ->
        val updated = connection.prepareStatement(
            "UPDATE discord_link_tokens SET used_at=? WHERE token_hash=? AND used_at IS NULL AND expires_at > ?",
        ).use { statement ->
            statement.setUtc(1, now)
            statement.setString(2, tokenHash)
            statement.setUtc(3, now)
            statement.executeUpdate()
        }
        if (updated == 1) matchedToken(connection, tokenHash) else classifyToken(connection, tokenHash, now)
    }

    override fun insertLink(link: DiscordAccountLink): LinkInsertOutcome {
        try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "INSERT INTO discord_account_links (player_uuid, player_name, discord_user_id, linked_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                ).use { statement ->
                    statement.setBytes(1, link.playerUuid.toBytes())
                    statement.setString(2, link.playerName)
                    statement.setLong(3, link.discordUserId)
                    statement.setUtc(4, link.linkedAt)
                    statement.setUtc(5, link.updatedAt)
                    statement.executeUpdate()
                }
            }
            return LinkInsertOutcome.CREATED
        } catch (violation: SQLIntegrityConstraintViolationException) {
            return when {
                findLinkByPlayer(link.playerUuid) != null -> LinkInsertOutcome.PLAYER_TAKEN
                findLinkByDiscord(link.discordUserId) != null -> LinkInsertOutcome.DISCORD_TAKEN
                else -> throw violation
            }
        }
    }

    override fun deleteLinkByPlayer(playerUuid: UUID): DiscordAccountLink? = deleteLink("player_uuid", playerUuid.toBytes())

    override fun deleteLinkByDiscord(discordUserId: Long): DiscordAccountLink? = deleteLink("discord_user_id", discordUserId)

    private fun deleteLink(column: String, key: Any): DiscordAccountLink? = transaction { connection ->
        val existing = connection.prepareStatement("$SELECT_LINK WHERE $column=?").use { statement ->
            if (key is ByteArray) statement.setBytes(1, key) else statement.setLong(1, key as Long)
            statement.executeQuery().use { result -> if (result.next()) result.toLink() else null }
        } ?: return@transaction null
        connection.prepareStatement("DELETE FROM discord_account_links WHERE $column=?").use { statement ->
            if (key is ByteArray) statement.setBytes(1, key) else statement.setLong(1, key as Long)
            statement.executeUpdate()
        }
        existing
    }

    private fun matchedToken(connection: Connection, tokenHash: String): LinkTokenConsumption =
        connection.prepareStatement("SELECT player_uuid, player_name FROM discord_link_tokens WHERE token_hash=?").use { statement ->
            statement.setString(1, tokenHash)
            statement.executeQuery().use { result ->
                if (result.next()) LinkTokenConsumption.Matched(result.getBytes("player_uuid").toUuid(), result.getString("player_name"))
                else LinkTokenConsumption.NotFound
            }
        }

    /** Classifies why the conditional update matched no row. */
    private fun classifyToken(connection: Connection, tokenHash: String, now: Instant): LinkTokenConsumption =
        connection.prepareStatement("SELECT used_at, expires_at FROM discord_link_tokens WHERE token_hash=?").use { statement ->
            statement.setString(1, tokenHash)
            statement.executeQuery().use { result ->
                when {
                    !result.next() -> LinkTokenConsumption.NotFound
                    result.getObject("used_at", LocalDateTime::class.java) != null -> LinkTokenConsumption.AlreadyUsed
                    else -> {
                        val expiresAt = result.getObject("expires_at", LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC)
                        if (expiresAt == null || !expiresAt.isAfter(now)) LinkTokenConsumption.Expired else LinkTokenConsumption.NotFound
                    }
                }
            }
        }

    private fun ResultSet.toLink(): DiscordAccountLink = DiscordAccountLink(
        playerUuid = getBytes("player_uuid").toUuid(),
        playerName = getString("player_name"),
        discordUserId = getLong("discord_user_id"),
        linkedAt = getObject("linked_at", LocalDateTime::class.java).toInstant(ZoneOffset.UTC),
        updatedAt = getObject("updated_at", LocalDateTime::class.java).toInstant(ZoneOffset.UTC),
    )

    private fun <T> transaction(block: (Connection) -> T): T {
        dataSource.connection.use { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                return block(connection).also { connection.commit() }
            } catch (failure: Throwable) {
                try { connection.rollback() } catch (rollback: Throwable) { failure.addSuppressed(rollback) }
                throw failure
            } finally {
                connection.autoCommit = previousAutoCommit
            }
        }
    }

    private fun PreparedStatement.setUtc(index: Int, instant: Instant) = setObject(index, LocalDateTime.ofInstant(instant, ZoneOffset.UTC))
    private fun UUID.toBytes(): ByteArray = ByteBuffer.allocate(16).putLong(mostSignificantBits).putLong(leastSignificantBits).array()
    private fun ByteArray.toUuid(): UUID = ByteBuffer.wrap(this).let { UUID(it.long, it.long) }

    private companion object {
        const val SELECT_LINK = "SELECT player_uuid, player_name, discord_user_id, linked_at, updated_at FROM discord_account_links"
    }
}
