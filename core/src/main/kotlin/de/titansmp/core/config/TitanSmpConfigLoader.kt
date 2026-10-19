package de.titansmp.core.config

import de.titansmp.api.server.ServerIdentity
import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.util.Properties

class TitanSmpConfigLoader {
    fun load(
        path: Path = Path.of("config", "titansmp.properties"),
        environment: Map<String, String> = System.getenv(),
    ): TitanSmpConfig {
        if (Files.exists(path) && !Files.isRegularFile(path)) {
            throw ConfigurationException("Configuration path '$path' is not a regular file")
        }

        val properties = Properties()
        if (Files.isRegularFile(path)) {
            Files.newBufferedReader(path, StandardCharsets.UTF_8).use(properties::load)
        }

        fun value(key: String, variable: String, default: String? = null): String =
            environment[variable]?.takeIf(String::isNotBlank)?.trim()
                ?: properties.getProperty(key)?.takeIf(String::isNotBlank)?.trim()
                ?: default
                ?: throw ConfigurationException("Missing required configuration '$key' (or '$variable')")

        fun integer(key: String, variable: String, default: Int): Int =
            value(key, variable, default.toString()).toIntOrNull()
                ?: throw ConfigurationException("Invalid integer for '$key' (or '$variable')")

        val identity = ServerIdentity(
            id = value("server.id", "TITANSMP_SERVER_ID"),
            type = value("server.type", "TITANSMP_SERVER_TYPE").toServerType("server.type"),
            environment = value("server.environment", "TITANSMP_ENVIRONMENT")
                .toServerEnvironment("server.environment"),
        )
        val redisPassword = environment["TITANSMP_REDIS_PASSWORD"]
            ?: properties.getProperty("redis.password")

        val networkName = value("network.name", "TITANSMP_NETWORK_NAME")
        val defaultNetworkId = networkName.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-').ifBlank { "titansmp" }
        return TitanSmpConfig(
            serverIdentity = identity,
            networkName = networkName,
            networkId = value("network.id", "TITANSMP_NETWORK_ID", defaultNetworkId),
            database = DatabaseConfig(
                host = value("database.host", "TITANSMP_DATABASE_HOST"),
                port = integer("database.port", "TITANSMP_DATABASE_PORT", 3306),
                database = value("database.name", "TITANSMP_DATABASE_NAME"),
                username = value("database.username", "TITANSMP_DATABASE_USERNAME"),
                password = value("database.password", "TITANSMP_DATABASE_PASSWORD"),
                maximumPoolSize = integer("database.maximumPoolSize", "TITANSMP_DATABASE_MAXIMUM_POOL_SIZE", 10),
            ),
            redis = RedisConfig(
                host = value("redis.host", "TITANSMP_REDIS_HOST"),
                port = integer("redis.port", "TITANSMP_REDIS_PORT", 6379),
                password = redisPassword?.takeIf(String::isNotBlank),
            ),
        )
    }
}
