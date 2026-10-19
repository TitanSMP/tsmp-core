package de.titansmp.core.redis

import io.lettuce.core.api.sync.RedisCommands
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection

/** A shared Redis command connection. Lettuce connections are safe for concurrent use. */
interface RedisService : AutoCloseable {
    val commands: RedisCommands<String, String>

    /** Opens an independent pub/sub connection; it must be closed by its owner. */
    fun openPubSubConnection(): StatefulRedisPubSubConnection<String, String> =
        throw UnsupportedOperationException("This Redis service does not provide pub/sub connections")

    fun start()
}

class RedisServiceException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
