package de.titansmp.core.discord.link

import de.titansmp.api.discord.DiscordAccountLink
import de.titansmp.core.database.DatabaseMigrator
import de.titansmp.core.database.DatabaseService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mariadb.MariaDBContainer
import java.nio.ByteBuffer
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcDiscordLinkRepositoryIntegrationTest {
    @Container
    private val mariaDb = MariaDBContainer("mariadb:11.4")

    private lateinit var dataSource: org.mariadb.jdbc.MariaDbDataSource
    private lateinit var repository: JdbcDiscordLinkRepository

    @BeforeEach
    fun setUp() {
        if (!::dataSource.isInitialized) {
            dataSource = org.mariadb.jdbc.MariaDbDataSource().apply {
                setUrl(mariaDb.jdbcUrl)
                setUser(mariaDb.username)
                setPassword(mariaDb.password)
            }
            DatabaseMigrator().migrate(object : DatabaseService {
                override val dataSource = this@JdbcDiscordLinkRepositoryIntegrationTest.dataSource
                override fun start() = Unit
                override fun close() = Unit
            })
        }
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("DELETE FROM discord_link_tokens")
                statement.executeUpdate("DELETE FROM discord_account_links")
                statement.executeUpdate("DELETE FROM players")
            }
        }
        repository = JdbcDiscordLinkRepository(dataSource)
    }

    @Test
    fun `link round trips through both directions`() {
        val player = insertPlayer("Henry")
        val now = Instant.parse("2026-10-19T20:00:00Z")
        assertEquals(LinkInsertOutcome.CREATED, repository.insertLink(DiscordAccountLink(player, "Henry", 100L, now, now)))

        assertEquals(100L, repository.findLinkByPlayer(player)?.discordUserId)
        assertEquals(player, repository.findLinkByDiscord(100L)?.playerUuid)
        assertNull(repository.findLinkByDiscord(101L))
    }

    @Test
    fun `unique constraints prevent a second link in either direction`() {
        val now = Instant.parse("2026-10-19T20:00:00Z")
        val first = insertPlayer("First")
        val second = insertPlayer("Second")

        assertEquals(LinkInsertOutcome.CREATED, repository.insertLink(DiscordAccountLink(first, "First", 1L, now, now)))
        assertEquals(LinkInsertOutcome.PLAYER_TAKEN, repository.insertLink(DiscordAccountLink(first, "First", 2L, now, now)))
        assertEquals(LinkInsertOutcome.DISCORD_TAKEN, repository.insertLink(DiscordAccountLink(second, "Second", 1L, now, now)))
    }

    @Test
    fun `a token is single use and expires`() {
        val player = insertPlayer("Henry")
        val now = Instant.parse("2026-10-19T20:00:00Z")
        repository.replaceLinkToken(hash("FRESH"), player, "Henry", now, now.plusSeconds(600))
        assertEquals(LinkTokenConsumption.Matched(player, "Henry"), repository.consumeToken(hash("FRESH"), now))
        assertEquals(LinkTokenConsumption.AlreadyUsed, repository.consumeToken(hash("FRESH"), now))

        repository.replaceLinkToken(hash("STALE"), player, "Henry", now, now.plusSeconds(1))
        assertEquals(LinkTokenConsumption.Expired, repository.consumeToken(hash("STALE"), now.plusSeconds(2)))
        assertEquals(LinkTokenConsumption.NotFound, repository.consumeToken(hash("MISSING"), now))
    }

    @Test
    fun `only one of many concurrent redemptions wins`() {
        val player = insertPlayer("Henry")
        val now = Instant.parse("2026-10-19T20:00:00Z")
        val tokenHash = hash("RACE")
        repository.replaceLinkToken(tokenHash, player, "Henry", now, now.plusSeconds(600))

        val executor = Executors.newFixedThreadPool(6)
        try {
            val futures = (0 until 6).map { executor.submit<LinkTokenConsumption> { repository.consumeToken(tokenHash, now) } }
            val matched = futures.map { it.get() }.count { it is LinkTokenConsumption.Matched }
            assertEquals(1, matched)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `delete removes the link in both directions`() {
        val player = insertPlayer("Henry")
        val now = Instant.parse("2026-10-19T20:00:00Z")
        repository.insertLink(DiscordAccountLink(player, "Henry", 7L, now, now))
        assertEquals(7L, repository.deleteLinkByDiscord(7L)?.discordUserId)
        assertNull(repository.findLinkByPlayer(player))
        repository.insertLink(DiscordAccountLink(player, "Henry", 8L, now, now))
        assertEquals(8L, repository.deleteLinkByPlayer(player)?.discordUserId)
    }

    private fun hash(raw: String) = DiscordLinkCodes.hash(raw)

    private fun insertPlayer(name: String): UUID {
        val uuid = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO players (uuid, username, first_join, last_seen, current_server, online) VALUES (?, ?, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), NULL, FALSE)",
            ).use { statement ->
                statement.setBytes(1, uuid.toBytes())
                statement.setString(2, name)
                statement.executeUpdate()
            }
        }
        return uuid
    }

    private fun UUID.toBytes(): ByteArray = ByteBuffer.allocate(16).putLong(mostSignificantBits).putLong(leastSignificantBits).array()
}
