package de.titansmp.core.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import de.titansmp.core.config.DatabaseConfig
import javax.sql.DataSource

class HikariDatabaseService(
    private val configuration: DatabaseConfig,
) : DatabaseService {
    @Volatile
    private var pool: HikariDataSource? = null

    override val dataSource: DataSource
        get() = pool ?: throw IllegalStateException("Database service has not been started")

    @Synchronized
    override fun start() {
        check(pool == null) { "Database service is already started" }

        val dataSource = try {
            HikariDataSource(createHikariConfig(configuration))
        } catch (exception: Exception) {
            throw DatabaseServiceException("Could not initialize the MariaDB connection pool", exception)
        }

        try {
            dataSource.connection.use { connection ->
                if (!connection.isValid(CONNECTION_VALIDATION_TIMEOUT_SECONDS)) {
                    throw DatabaseServiceException("MariaDB connection validation failed")
                }
            }
            pool = dataSource
        } catch (exception: Exception) {
            dataSource.close()
            if (exception is DatabaseServiceException) throw exception
            throw DatabaseServiceException("Could not connect to the configured MariaDB database", exception)
        }
    }

    @Synchronized
    override fun close() {
        val currentPool = pool
        pool = null
        currentPool?.close()
    }

    internal fun createHikariConfig(configuration: DatabaseConfig): HikariConfig = HikariConfig().apply {
        jdbcUrl = configuration.jdbcUrl
        username = configuration.username
        password = configuration.password
        maximumPoolSize = configuration.maximumPoolSize
        minimumIdle = 1.coerceAtMost(configuration.maximumPoolSize)
        connectionTimeout = CONNECTION_TIMEOUT_MILLIS
        initializationFailTimeout = INITIALIZATION_FAIL_TIMEOUT_MILLIS
        poolName = POOL_NAME
    }

    private companion object {
        const val CONNECTION_VALIDATION_TIMEOUT_SECONDS = 5
        const val CONNECTION_TIMEOUT_MILLIS = 5_000L
        const val INITIALIZATION_FAIL_TIMEOUT_MILLIS = 1L
        const val POOL_NAME = "TitanSMP-MariaDB"
    }
}
