package de.titansmp.core.stats

import de.titansmp.api.stats.StatType
import de.titansmp.core.database.DatabaseMigrator
import de.titansmp.core.database.DatabaseService
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mariadb.MariaDBContainer
import java.nio.ByteBuffer
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Verifies the V7 migration and the atomic, race-safe increments against a real MariaDB.
 * Skipped automatically when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcStatsRepositoryIntegrationTest {
    @Container
    private val mariaDb = MariaDBContainer("mariadb:11.4")

    private lateinit var dataSource: org.mariadb.jdbc.MariaDbDataSource
    private lateinit var repository: JdbcStatsRepository
    private val concurrencyExecutor = Executors.newFixedThreadPool(12) { task ->
        Thread(task, "stats-integration-test").apply { isDaemon = true }
    }

    @BeforeEach
    fun setUp() {
        if (!::dataSource.isInitialized) {
            dataSource = org.mariadb.jdbc.MariaDbDataSource().apply {
                setUrl(mariaDb.jdbcUrl)
                setUser(mariaDb.username)
                setPassword(mariaDb.password)
            }
            DatabaseMigrator().migrate(object : DatabaseService {
                override val dataSource = this@JdbcStatsRepositoryIntegrationTest.dataSource
                override fun start() = Unit
                override fun close() = Unit
            })
        }
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("DELETE FROM player_stats")
                statement.executeUpdate("DELETE FROM players")
            }
        }
        repository = JdbcStatsRepository(dataSource)
    }

    @AfterAll
    fun tearDown() {
        concurrencyExecutor.shutdownNow()
    }

    @Test
    fun `a player without a row reads null and increment creates the row`() {
        val player = insertPlayer("NewStatsPlayer")
        assertEquals(null, repository.find(player))

        val stats = repository.increment(player, StatType.BLOCKS_MINED, 7, Instant.now())

        assertEquals(7, stats.blocksMined)
        assertEquals(7, repository.find(player)!!.blocksMined)
        assertEquals(1, count("player_stats"))
    }

    @Test
    fun `incrementAll applies several columns in one atomic upsert`() {
        val player = insertPlayer("MultiStat")
        repository.incrementAll(player, mapOf(StatType.BLOCKS_MINED to 5, StatType.MOBS_KILLED to 2), Instant.now())
        val stats = repository.incrementAll(player, mapOf(StatType.BLOCKS_MINED to 3, StatType.DEATHS to 1), Instant.now())

        assertEquals(8, stats.blocksMined)
        assertEquals(2, stats.mobsKilled)
        assertEquals(1, stats.deaths)
    }

    @Test
    fun `parallel increments of the same statistic never lose an update`() {
        val player = insertPlayer("ConcurrentStats")
        runConcurrently(40) { repository.increment(player, StatType.BLOCKS_MINED, 1, Instant.now()) }

        assertEquals(40, repository.find(player)!!.blocksMined)
    }

    @Test
    fun `parallel increments across different columns all survive`() {
        val player = insertPlayer("ConcurrentColumns")
        runConcurrently(20) { index ->
            when (index % 4) {
                0 -> repository.increment(player, StatType.BLOCKS_MINED, 1, Instant.now())
                1 -> repository.increment(player, StatType.MOBS_KILLED, 1, Instant.now())
                2 -> repository.increment(player, StatType.DEATHS, 1, Instant.now())
                else -> repository.increment(player, StatType.DAMAGE_DEALT, 1, Instant.now())
            }
        }

        val stats = repository.find(player)!!
        assertEquals(60, stats.blocksMined + stats.mobsKilled + stats.deaths + stats.damageDealt)
    }

    @Test
    fun `leaderboard uses sql ordering with a deterministic uuid tie breaker`() {
        val low = insertPlayer("Low", UUID(0x1000L, 0x1000L))
        val high = insertPlayer("High", UUID(0x2000L, 0x2000L))
        val top = insertPlayer("Top")
        repository.increment(low, StatType.PLAYERS_KILLED, 10, Instant.now())
        repository.increment(high, StatType.PLAYERS_KILLED, 10, Instant.now())
        repository.increment(top, StatType.PLAYERS_KILLED, 99, Instant.now())
        repository.increment(insertPlayer("Zero"), StatType.PLAYERS_KILLED, 0, Instant.now())

        val entries = repository.top(StatType.PLAYERS_KILLED, 10)

        assertEquals(listOf(top, low, high), entries.map { it.playerUuid })
        assertEquals(listOf(1, 2, 3), entries.map { it.rank })
        assertEquals("Top", entries.first().username)
        assertTrue(entries.all { it.statType == StatType.PLAYERS_KILLED })
    }

    private fun runConcurrently(count: Int, block: (Int) -> Unit) {
        val start = CountDownLatch(1)
        val futures = (0 until count).map { index ->
            concurrencyExecutor.submit {
                start.await()
                block(index)
            }
        }
        start.countDown()
        futures.forEach { it.get(30, TimeUnit.SECONDS) }
    }

    private fun insertPlayer(name: String, uuid: UUID = UUID.randomUUID()): UUID {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO players (uuid, username, first_join, last_seen, current_server, online) " +
                    "VALUES (?, ?, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), 'smp-01', TRUE)",
            ).use { statement ->
                statement.setBytes(1, uuid.asBytes())
                statement.setString(2, name)
                statement.executeUpdate()
            }
        }
        return uuid
    }

    private fun count(table: String): Int = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM $table").use { result ->
                result.next()
                result.getInt(1)
            }
        }
    }

    private fun UUID.asBytes(): ByteArray = ByteBuffer.allocate(16).putLong(mostSignificantBits).putLong(leastSignificantBits).array()
}
