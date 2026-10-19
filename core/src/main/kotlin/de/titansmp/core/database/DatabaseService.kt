package de.titansmp.core.database

import javax.sql.DataSource

/** The shared MariaDB pool used by core and feature repositories. */
interface DatabaseService : AutoCloseable {
    val dataSource: DataSource

    fun start()
}

class DatabaseServiceException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
