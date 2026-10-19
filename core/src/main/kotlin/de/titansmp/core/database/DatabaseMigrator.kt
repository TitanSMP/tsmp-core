package de.titansmp.core.database

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.MigrateResult
import javax.sql.DataSource

class DatabaseMigrator(
    private val locations: Array<String> = arrayOf(DEFAULT_MIGRATION_LOCATION),
) {
    fun migrate(databaseService: DatabaseService): MigrateResult =
        try {
            createFlyway(databaseService.dataSource).migrate()
        } catch (exception: Exception) {
            throw DatabaseMigrationException("Database migrations failed", exception)
        }

    internal fun createFlyway(dataSource: DataSource): Flyway = Flyway.configure()
        .dataSource(dataSource)
        .locations(*locations)
        .validateMigrationNaming(true)
        .cleanDisabled(true)
        .baselineOnMigrate(false)
        .outOfOrder(false)
        .load()

    companion object {
        const val DEFAULT_MIGRATION_LOCATION = "classpath:db/migration"
    }
}

class DatabaseMigrationException(message: String, cause: Throwable) : RuntimeException(message, cause)
