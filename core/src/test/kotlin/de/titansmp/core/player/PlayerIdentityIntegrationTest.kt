package de.titansmp.core.player

import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mariadb.MariaDBContainer
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlayerIdentityIntegrationTest {
    @Container
    private val mariaDb = MariaDBContainer("mariadb:11.4")

    private lateinit var dataSource: HikariDataSource
    private lateinit var repository: JdbcPlayerRepository
    private lateinit var service: DefaultPlayerService

    @BeforeEach
    fun setUp() {
        if (!::dataSource.isInitialized) {
            dataSource = HikariDataSource().apply {
                jdbcUrl = mariaDb.jdbcUrl
                username = mariaDb.username
                password = mariaDb.password
                maximumPoolSize = 2
            }
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate()
        }
        dataSource.connection.use { it.createStatement().use { statement -> statement.executeUpdate("DELETE FROM players") } }
        repository = JdbcPlayerRepository(dataSource)
        service = DefaultPlayerService(repository, Clock.fixed(now, ZoneOffset.UTC))
    }

    @AfterAll
    fun tearDown() {
        if (::dataSource.isInitialized) dataSource.close()
    }

    @Test
    fun `create and load preserves UUID and first join while updating username and last seen`() {
        val uuid = UUID.randomUUID()
        val created = service.createOrLoad(uuid, "TitanOne")
        assertEquals(uuid, created.uuid)
        assertEquals("TitanOne", created.username)
        assertEquals(now, created.firstJoin)
        assertEquals(now, created.lastSeen)

        val loaded = service.createOrLoad(uuid, "TitanTwo")
        assertEquals(uuid, loaded.uuid)
        assertEquals("TitanTwo", loaded.username)
        assertEquals(created.firstJoin, loaded.firstJoin)
        assertEquals(now, loaded.lastSeen)
        assertEquals(loaded, service.findPlayer(uuid))
        assertEquals(loaded, service.findPlayerByName("TitanTwo"))
    }

    @Test
    fun `online and offline state uses configured server identity`() {
        val uuid = UUID.randomUUID()
        service.createOrLoad(uuid, "TitanOne")
        service.setOnline(uuid, "smp-01")
        assertTrue(service.getPlayer(uuid).online)
        assertEquals("smp-01", service.getPlayer(uuid).currentServer)

        assertTrue(service.setOffline(uuid, "smp-01"))
        val offline = service.getPlayer(uuid)
        assertFalse(offline.online)
        assertNull(offline.currentServer)
    }

    @Test
    fun `stale server disconnect cannot clear a newer server owner`() {
        val uuid = UUID.randomUUID()
        service.createOrLoad(uuid, "TitanOne")
        service.setOnline(uuid, "lobby-01")

        assertFalse(service.setOffline(uuid, "smp-01"))
        assertEquals("lobby-01", service.getPlayer(uuid).currentServer)
    }

    @Test
    fun `last seen can be updated and UUID lookup is stable`() {
        val uuid = UUID.randomUUID()
        service.createOrLoad(uuid, "TitanOne")
        val row = service.findPlayer(uuid)
        assertNotNull(row)
        assertTrue(repository.updateLastSeen(uuid, now.plusSeconds(20)))
        assertEquals(now.plusSeconds(20), service.getPlayer(uuid).lastSeen)
        assertNull(service.findPlayer(UUID.randomUUID()))
    }

    companion object {
        private val now: Instant = Instant.parse("2026-01-02T03:04:05Z")
    }
}
