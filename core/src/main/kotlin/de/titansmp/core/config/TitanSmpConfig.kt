package de.titansmp.core.config

import de.titansmp.api.server.ServerEnvironment
import de.titansmp.api.server.ServerIdentity
import de.titansmp.api.server.ServerType

class TitanSmpConfig(
    val serverIdentity: ServerIdentity,
    val networkName: String,
    val database: DatabaseConfig,
    val redis: RedisConfig,
) {
    init {
        require(networkName.isNotBlank()) { "Network name must not be blank" }
    }

    override fun toString(): String =
        "TitanSmpConfig(serverIdentity=$serverIdentity, networkName=$networkName, database=<redacted>, redis=<redacted>)"
}

class DatabaseConfig(
    val host: String,
    val port: Int,
    val database: String,
    val username: String,
    val password: String,
    val maximumPoolSize: Int,
) {
    init {
        require(host.isNotBlank()) { "Database host must not be blank" }
        require(port in 1..65535) { "Database port must be between 1 and 65535" }
        require(database.isNotBlank()) { "Database name must not be blank" }
        require(username.isNotBlank()) { "Database username must not be blank" }
        require(password.isNotBlank()) { "Database password must not be blank" }
        require(maximumPoolSize > 0) { "Database maximum pool size must be positive" }
    }

    val jdbcUrl: String
        get() = "jdbc:mariadb://$host:$port/$database"

    override fun toString(): String =
        "DatabaseConfig(host=$host, port=$port, database=$database, username=$username, password=<redacted>, maximumPoolSize=$maximumPoolSize)"
}

class RedisConfig(
    val host: String,
    val port: Int,
    val password: String?,
) {
    init {
        require(host.isNotBlank()) { "Redis host must not be blank" }
        require(port in 1..65535) { "Redis port must be between 1 and 65535" }
    }

    override fun toString(): String =
        "RedisConfig(host=$host, port=$port, password=<redacted>)"
}

internal fun String.toServerType(key: String): ServerType =
    enumValueOfOrConfigError(key) { value -> ServerType.valueOf(value.uppercase()) }

internal fun String.toServerEnvironment(key: String): ServerEnvironment =
    enumValueOfOrConfigError(key) { value -> ServerEnvironment.valueOf(value.uppercase()) }

private inline fun <T> String.enumValueOfOrConfigError(key: String, parse: (String) -> T): T =
    try {
        parse(trim())
    } catch (exception: IllegalArgumentException) {
        throw ConfigurationException("Invalid value for '$key'", exception)
    }

class ConfigurationException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)
