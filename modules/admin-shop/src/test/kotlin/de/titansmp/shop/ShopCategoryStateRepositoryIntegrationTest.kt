package de.titansmp.shop

import de.titansmp.core.database.DatabaseMigrator
import de.titansmp.core.database.DatabaseService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mariadb.MariaDBContainer
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopCategoryStateRepositoryIntegrationTest {
    @Container private val maria=MariaDBContainer("mariadb:11.4")
    private lateinit var dataSource:org.mariadb.jdbc.MariaDbDataSource
    private lateinit var repository:ShopCategoryStateRepository

    @BeforeAll fun migrate() {
        dataSource=org.mariadb.jdbc.MariaDbDataSource().apply { setUrl(maria.jdbcUrl);setUser(maria.username);setPassword(maria.password) }
        DatabaseMigrator().migrate(object:DatabaseService { override val dataSource:DataSource get()=this@ShopCategoryStateRepositoryIntegrationTest.dataSource;override fun start()=Unit;override fun close()=Unit })
        repository=ShopCategoryStateRepository(dataSource)
    }
    @BeforeEach fun clear() { dataSource.connection.use { it.createStatement().use { s->s.executeUpdate("DELETE FROM shop_category_state_audit");s.executeUpdate("DELETE FROM shop_category_state") } } }

    @Test fun `missing category rows default to enabled`() {
        val states=repository.load(setOf("food","end"))
        assertEquals(true,states.getValue("food").enabled)
        assertEquals(null,states.getValue("food").updatedBy)
    }

    @Test fun `disable enable and status persist and audit actors`() {
        val actor=UUID.randomUUID();val now=Instant.parse("2026-10-21T10:00:00Z")
        val disabled=repository.set("end",false,actor,"smp-01",now)
        assertEquals(false,disabled.enabled)
        assertEquals(disabled,repository.load(setOf("end")).getValue("end"))
        val enabled=repository.set("end",true,actor,"smp-02",now.plusSeconds(5))
        assertEquals(true,enabled.enabled)
        dataSource.connection.use { c -> c.prepareStatement("SELECT old_enabled,new_enabled,updated_by,server_id FROM shop_category_state_audit ORDER BY id").use { s->s.executeQuery().use { r ->
            assertEquals(2, (1..2).count { r.next() })
            r.beforeFirst();r.next();assertEquals(true,r.getBoolean("old_enabled"));assertEquals(false,r.getBoolean("new_enabled"));assertEquals("smp-01",r.getString("server_id"))
            r.next();assertEquals(false,r.getBoolean("old_enabled"));assertEquals(true,r.getBoolean("new_enabled"));assertEquals("smp-02",r.getString("server_id"))
        } } }
    }
}
