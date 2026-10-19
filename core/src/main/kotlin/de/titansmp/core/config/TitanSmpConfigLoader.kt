package de.titansmp.core.config

import de.titansmp.api.server.ServerIdentity
import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.util.Properties

class TitanSmpConfigLoader {
    fun loadRedisEventConfiguration(
        path: Path = Path.of("config", "titansmp.properties"),
        environment: Map<String, String> = System.getenv(),
    ): RedisEventConfiguration {
        val properties = Properties()
        if (Files.isRegularFile(path)) Files.newBufferedReader(path, StandardCharsets.UTF_8).use(properties::load)
        fun configured(property: String, variable: String, default: String? = null): String =
            environment[variable]?.takeIf(String::isNotBlank)?.trim()
                ?: properties.getProperty(property)?.takeIf(String::isNotBlank)?.trim()
                ?: default
                ?: throw ConfigurationException("Missing required configuration '$property' (or '$variable')")
        val networkName = configured("network.name", "TITANSMP_NETWORK_NAME", "TitanSMP")
        val derivedNetworkId = networkName.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-').ifBlank { "titansmp" }
        val timeout = configured("data-bridge.transfer.timeout-ms", "TITANSMP_TRANSFER_TIMEOUT_MS", "5000").toLongOrNull()
            ?: throw ConfigurationException("Invalid integer for 'data-bridge.transfer.timeout-ms'")
        return RedisEventConfiguration(
            serverId = configured("server.id", "TITANSMP_SERVER_ID", "velocity"),
            networkId = configured("network.id", "TITANSMP_NETWORK_ID", derivedNetworkId),
            redis = RedisConfig(
                configured("redis.host", "TITANSMP_REDIS_HOST", "127.0.0.1"),
                configured("redis.port", "TITANSMP_REDIS_PORT", "6379").toIntOrNull()
                    ?: throw ConfigurationException("Invalid integer for 'redis.port'"),
                (environment["TITANSMP_REDIS_PASSWORD"] ?: properties.getProperty("redis.password"))?.takeIf(String::isNotBlank),
            ),
            playerTransferTimeoutMs = timeout,
        )
    }

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
            playerTransferTimeoutMs = integer("data-bridge.transfer.timeout-ms", "TITANSMP_TRANSFER_TIMEOUT_MS", 5_000).toLong(),
            chat = ChatConfig(
                rateLimitEnabled = value("chat.rate-limit.enabled", "TITANSMP_CHAT_RATE_LIMIT_ENABLED", "true").toBooleanStrictOrConfigError("chat.rate-limit.enabled"),
                messagesPerWindow = integer("chat.rate-limit.messages-per-window", "TITANSMP_CHAT_MESSAGES_PER_WINDOW", 5),
                windowMs = value("chat.rate-limit.window-ms", "TITANSMP_CHAT_WINDOW_MS", "3000").toLongOrNull()
                    ?: throw ConfigurationException("Invalid integer for 'chat.rate-limit.window-ms'"),
                maxMessageLength = integer("chat.max-message-length", "TITANSMP_CHAT_MAX_MESSAGE_LENGTH", 256),
            ),
        )
    }
}

private fun String.toBooleanStrictOrConfigError(key: String): Boolean = try {
    toBooleanStrict()
} catch (failure: IllegalArgumentException) {
    throw ConfigurationException("Invalid boolean for '$key'", failure)
}

data class RedisEventConfiguration(
    val serverId: String,
    val networkId: String,
    val redis: RedisConfig,
    val playerTransferTimeoutMs: Long,
) {
    init {
        require(serverId.isNotBlank())
        require(networkId.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}")))
        require(playerTransferTimeoutMs in 250..60_000)
    }
}
