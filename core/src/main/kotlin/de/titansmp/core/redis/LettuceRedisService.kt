package de.titansmp.core.redis

import de.titansmp.core.config.RedisConfig
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands

class LettuceRedisService(
    private val configuration: RedisConfig,
) : RedisService {
    @Volatile
    private var client: RedisClient? = null

    @Volatile
    private var connection: StatefulRedisConnection<String, String>? = null

    override val commands: RedisCommands<String, String>
        get() = connection?.sync() ?: throw IllegalStateException("Redis service has not been started")

    @Synchronized
    override fun start() {
        check(client == null && connection == null) { "Redis service is already started" }

        val newClient = try {
            RedisClient.create(createRedisUri(configuration))
        } catch (exception: Exception) {
            throw RedisServiceException("Could not initialize the Redis client", exception)
        }

        var newConnection: StatefulRedisConnection<String, String>? = null
        try {
            newConnection = newClient.connect()
            val response = newConnection.sync().ping()
            if (response != "PONG") {
                newConnection.close()
                throw RedisServiceException("Redis health check returned an unexpected response")
            }
            client = newClient
            connection = newConnection
        } catch (exception: Exception) {
            try {
                newConnection?.close()
            } finally {
                newClient.shutdown()
            }
            if (exception is RedisServiceException) throw exception
            throw RedisServiceException("Could not connect to the configured Redis server", exception)
        }
    }

    @Synchronized
    override fun close() {
        val currentConnection = connection
        val currentClient = client
        connection = null
        client = null

        try {
            currentConnection?.close()
        } finally {
            currentClient?.shutdown()
        }
    }

    internal fun createRedisUri(configuration: RedisConfig): RedisURI {
        val builder = RedisURI.Builder.redis(configuration.host, configuration.port)
        configuration.password?.let { builder.withPassword(it.toCharArray()) }
        return builder.build()
    }
}
