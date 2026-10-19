package de.titansmp.core.lifecycle

import de.titansmp.api.event.EventBus
import de.titansmp.core.config.RedisConfig
import de.titansmp.core.config.RedisEventConfiguration
import de.titansmp.core.config.TitanSmpConfigLoader
import de.titansmp.core.event.LettuceRedisPubSubTransport
import de.titansmp.core.event.RedisEventBus
import de.titansmp.core.redis.LettuceRedisService
import de.titansmp.core.redis.RedisService

/** Redis-only runtime for processes such as Velocity that coordinate events but do not persist domain state. */
class TitanSmpEventRuntime(
    private val configLoader: () -> RedisEventConfiguration = { TitanSmpConfigLoader().loadRedisEventConfiguration() },
    private val redisFactory: (RedisConfig) -> RedisService = ::LettuceRedisService,
    private val eventBusFactory: (RedisService, String) -> EventBus = { redis, networkId ->
        RedisEventBus(networkId, LettuceRedisPubSubTransport(redis.openPubSubConnection()))
    },
) : AutoCloseable {
    @Volatile private var redis: RedisService? = null
    @Volatile private var eventBusService: EventBus? = null
    @Volatile private var configuration: RedisEventConfiguration? = null

    val serverId: String get() = requireNotNull(configuration) { "TitanSMP event runtime is not ready" }.serverId
    val playerTransferTimeoutMs: Long get() = requireNotNull(configuration) { "TitanSMP event runtime is not ready" }.playerTransferTimeoutMs

    @Synchronized
    fun start(): EventBus {
        check(configuration == null) { "TitanSMP event runtime is already started" }
        val config = configLoader()
        configuration = config
        try {
            val connection = redisFactory(config.redis)
            redis = connection
            connection.start()
            val bus = eventBusFactory(connection, config.networkId)
            eventBusService = bus
            return bus
        } catch (failure: Throwable) {
            try { eventBusService?.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
            eventBusService = null
            try { redis?.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
            redis = null
            configuration = null
            throw RuntimeStartupException("TitanSMP Redis event runtime failed to start", failure)
        }
    }

    @Synchronized
    override fun close() {
        val bus = eventBusService
        val connection = redis
        eventBusService = null
        redis = null
        configuration = null
        var failure: Throwable? = null
        try { bus?.close() } catch (problem: Throwable) { failure = problem }
        try { connection?.close() } catch (problem: Throwable) {
            if (failure == null) failure = problem else failure.addSuppressed(problem)
        }
        if (failure != null) throw RuntimeShutdownException("TitanSMP Redis event runtime failed to stop", failure)
    }
}
