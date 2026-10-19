package de.titansmp.core.enderchest

import de.titansmp.api.enderchest.EnderChestStorage
import de.titansmp.api.enderchest.EnderChestStorageConflictException
import de.titansmp.api.enderchest.EnderChestSessionOwnershipException
import de.titansmp.api.enderchest.SerializedEnderChestItem
import de.titansmp.core.database.DatabaseMigrator
import de.titansmp.core.database.DatabaseService
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Assertions.assertThrows
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mariadb.MariaDBContainer
import java.util.UUID
import java.nio.ByteBuffer

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcEnderChestRepositoryIntegrationTest {
    @Container
    private val mariaDb = MariaDBContainer("mariadb:11.4")

    private lateinit var dataSource: org.mariadb.jdbc.MariaDbDataSource
    private lateinit var repository: JdbcEnderChestRepository

    @BeforeEach
    fun setUp() {
        if (!::dataSource.isInitialized) {
            dataSource = org.mariadb.jdbc.MariaDbDataSource().apply {
                setUrl(mariaDb.jdbcUrl)
                setUser(mariaDb.username)
                setPassword(mariaDb.password)
            }
            DatabaseMigrator().migrate(object : DatabaseService {
                override val dataSource = this@JdbcEnderChestRepositoryIntegrationTest.dataSource
                override fun start() = Unit
                override fun close() = Unit
            })
        }
        dataSource.connection.use { it.createStatement().use { statement -> statement.executeUpdate("DELETE FROM ender_chest_storage") } }
        repository = JdbcEnderChestRepository(dataSource)
    }

    @AfterAll
    fun tearDown() {
        // MariaDbDataSource is connection-factory based and holds no pool to close.
    }

    @Test
    fun `unknown player loads 54 empty slots and item payload persists`() {
        val uuid = UUID.randomUUID()
        createPlayer(uuid)
        val empty = repository.load(uuid, "smp-01")
        assertEquals(54, empty.slots.size)
        assertEquals(54, empty.slots.count { it == null })

        val slots = empty.slots.toMutableList()
        slots[40] = SerializedEnderChestItem(byteArrayOf(11, 12, 13))
        slots[53] = SerializedEnderChestItem(byteArrayOf(21, 22))
        assertEquals(1, repository.save(empty.copy(slots = slots), "smp-01").revision)

        val loaded = repository.load(uuid, "smp-01")
        assertEquals(52, loaded.slots.count { it == null })
        assertEquals(SerializedEnderChestItem(byteArrayOf(11, 12, 13)), loaded.slots[40])
        assertEquals(SerializedEnderChestItem(byteArrayOf(21, 22)), loaded.slots[53])
        assertNull(loaded.slots[0])
    }

    @Test
    fun `revision compare and set prevents an older server from overwriting newer data`() {
        val uuid = UUID.randomUUID()
        createPlayer(uuid)
        val initial = EnderChestStorage.empty(uuid)
        repository.save(initial, "smp-01")
        val serverA = repository.load(uuid, "smp-01")
        val serverB = repository.load(uuid, "smp-01")
        val newA = serverA.copy(slots = serverA.slots.toMutableList().also {
            it[40] = SerializedEnderChestItem(byteArrayOf(1))
        })
        val oldB = serverB.copy(slots = serverB.slots.toMutableList().also {
            it[41] = SerializedEnderChestItem(byteArrayOf(2))
        })
        repository.save(newA, "smp-01")

        assertThrows(EnderChestStorageConflictException::class.java) { repository.save(oldB, "smp-01") }
        val stored = repository.load(uuid, "smp-01")
        assertEquals(SerializedEnderChestItem(byteArrayOf(1)), stored.slots[40])
        assertNull(stored.slots[41])
        assertEquals(2, stored.revision)
    }

    @Test
    fun `backend which lost player presence ownership cannot load or save`() {
        val uuid = UUID.randomUUID()
        createPlayer(uuid)
        repository.save(EnderChestStorage.empty(uuid), "smp-01")
        val staleSnapshot = repository.load(uuid, "smp-01")
        dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE players SET current_server = 'smp-02' WHERE uuid = ?").use { statement ->
                statement.setBytes(1, uuid.toBytes())
                statement.executeUpdate()
            }
        }

        assertThrows(EnderChestSessionOwnershipException::class.java) { repository.load(uuid, "smp-01") }
        assertThrows(EnderChestStorageConflictException::class.java) {
            repository.save(staleSnapshot.copy(slots = staleSnapshot.slots.toMutableList().also {
                it[40] = SerializedEnderChestItem(byteArrayOf(99))
            }), "smp-01")
        }
        assertNull(repository.load(uuid, "smp-02").slots[40])
    }

    private fun createPlayer(uuid: UUID) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO players (uuid, username, first_join, last_seen, current_server, online) " +
                    "VALUES (?, ?, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), 'smp-01', TRUE)",
            ).use { statement ->
                statement.setBytes(1, ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array())
                statement.setString(2, "ChestTest")
                statement.executeUpdate()
            }
        }
    }

    private fun UUID.toBytes(): ByteArray =
        ByteBuffer.allocate(16).putLong(mostSignificantBits).putLong(leastSignificantBits).array()
}
