package de.titansmp.core.redis

import io.lettuce.core.api.sync.RedisCommands

/** A shared Redis command connection. Lettuce connections are safe for concurrent use. */
interface RedisService : AutoCloseable {
    val commands: RedisCommands<String, String>

    fun start()
}

class RedisServiceException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
