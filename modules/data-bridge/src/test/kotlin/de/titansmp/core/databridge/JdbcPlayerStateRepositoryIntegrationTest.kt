package de.titansmp.core.databridge

import de.titansmp.api.databridge.PlayerState
import de.titansmp.api.databridge.PlayerStateConflictException
import de.titansmp.api.databridge.PlayerStateOwnershipException
import de.titansmp.api.databridge.SerializedPlayerItem
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mariadb.MariaDBContainer
import org.mariadb.jdbc.MariaDbDataSource
import java.util.UUID

@Testcontainers(disabledWithoutDocker = true)
class JdbcPlayerStateRepositoryIntegrationTest {
    companion object {
        @Container @JvmStatic
        val database = MariaDBContainer("mariadb:11.4")
    }

    private lateinit var source: MariaDbDataSource
    private lateinit var repository: JdbcPlayerStateRepository
    private val playerUuid = UUID.randomUUID()

    @BeforeEach fun setUp() {
        source = MariaDbDataSource().apply { setUrl(database.jdbcUrl); setUser(database.username); setPassword(database.password) }
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate()
        source.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO players(uuid, username, first_join, last_seen, current_server, online) VALUES (?, 'Player', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), 'smp-01', TRUE)",
            ).use { statement ->
                statement.setBytes(1, java.nio.ByteBuffer.allocate(16).putLong(playerUuid.mostSignificantBits).putLong(playerUuid.leastSignificantBits).array())
                statement.executeUpdate()
            }
        }
        repository = JdbcPlayerStateRepository(source)
    }

    @Test fun `save load and stale revision are atomic`() {
        val state = fullState()
        assertNull(repository.load(playerUuid, "smp-01"))
        val saved = repository.save(state, "smp-01")
        assertEquals(1, saved.revision)
        assertEquals(saved, repository.load(playerUuid, "smp-01"))
        val revisionTwo = repository.save(saved.copy(level = 38), "smp-01")
        assertEquals(2, revisionTwo.revision)
        assertThrows(PlayerStateConflictException::class.java) { repository.save(saved.copy(level = 1), "smp-01") }
        assertEquals(revisionTwo, repository.load(playerUuid, "smp-01"))
    }

    @Test fun `server ownership is checked for reads and writes`() {
        assertThrows(PlayerStateOwnershipException::class.java) { repository.load(playerUuid, "smp-02") }
        assertThrows(PlayerStateOwnershipException::class.java) { repository.save(fullState(), "smp-02") }
    }

    private fun fullState() = PlayerState(
        playerUuid, List(36) { if (it == 8) SerializedPlayerItem(byteArrayOf(1, 2, 3)) else null },
        listOf(SerializedPlayerItem(byteArrayOf(4)), null, null, SerializedPlayerItem(byteArrayOf(5))),
        SerializedPlayerItem(byteArrayOf(6)), 15.25, 14, .75f, 42, 1889,
    )
}
