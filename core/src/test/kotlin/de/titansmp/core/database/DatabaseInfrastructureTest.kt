package de.titansmp.core.database

import com.zaxxer.hikari.HikariConfig
import de.titansmp.core.config.DatabaseConfig
import java.lang.reflect.Proxy
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabaseInfrastructureTest {
    private val databaseConfig = DatabaseConfig(
        host = "127.0.0.1",
        port = 3306,
        database = "titansmp",
        username = "example-user",
        password = "do-not-log-this",
        maximumPoolSize = 6,
    )

    @Test
    fun `configures one bounded Hikari pool without putting credentials in the JDBC URL`() {
        val hikariConfig: HikariConfig = HikariDatabaseService(databaseConfig).createHikariConfig(databaseConfig)

        assertEquals("jdbc:mariadb://127.0.0.1:3306/titansmp", hikariConfig.jdbcUrl)
        assertEquals(6, hikariConfig.maximumPoolSize)
        assertEquals("example-user", hikariConfig.username)
        assertEquals("do-not-log-this", hikariConfig.password)
        assertFalse(hikariConfig.jdbcUrl.contains(databaseConfig.password))
        assertFalse(databaseConfig.toString().contains(databaseConfig.password))
    }

    @Test
    fun `Flyway uses versioned classpath migrations and disables clean`() {
        val flyway = DatabaseMigrator().createFlyway(unusedDataSource())

        assertEquals(
            DatabaseMigrator.DEFAULT_MIGRATION_LOCATION,
            flyway.configuration.locations.single().toString(),
        )
        assertTrue(flyway.configuration.isCleanDisabled())
        assertFalse(flyway.configuration.isOutOfOrder())
        assertFalse(flyway.configuration.isBaselineOnMigrate())
        assertTrue(flyway.configuration.isValidateMigrationNaming())
    }

    private fun unusedDataSource(): DataSource = Proxy.newProxyInstance(
        DataSource::class.java.classLoader,
        arrayOf(DataSource::class.java),
    ) { _, method, _ ->
        throw UnsupportedOperationException("Unexpected DataSource call: ${method.name}")
    } as DataSource
}
