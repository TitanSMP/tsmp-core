package de.titansmp.core.database

import de.titansmp.core.config.TitanSmpConfigLoader

/** Entry point for the explicit local/operations migration Gradle task. */
fun main() {
    val configuration = TitanSmpConfigLoader().load()
    HikariDatabaseService(configuration.database).use { database ->
        database.start()
        DatabaseMigrator().migrate(database)
        println("TitanSMP database migrations completed")
    }
}
