package de.titansmp.core.event

import io.lettuce.core.pubsub.RedisPubSubListener
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import java.util.concurrent.CompletionStage

interface RedisPubSubTransport : AutoCloseable {
    fun subscribe(channel: String, receiver: (String) -> Unit)
    fun publish(channel: String, message: String): CompletionStage<Long>
}

class LettuceRedisPubSubTransport(
    private val connection: StatefulRedisPubSubConnection<String, String>,
) : RedisPubSubTransport {
    override fun subscribe(channel: String, receiver: (String) -> Unit) {
        connection.addListener(object : RedisPubSubListener<String, String> {
            override fun message(messageChannel: String, message: String) {
                if (messageChannel == channel) receiver(message)
            }
            override fun message(pattern: String, messageChannel: String, message: String) = Unit
            override fun subscribed(channel: String, count: Long) = Unit
            override fun psubscribed(pattern: String, count: Long) = Unit
            override fun unsubscribed(channel: String, count: Long) = Unit
            override fun punsubscribed(pattern: String, count: Long) = Unit
        })
        connection.sync().subscribe(channel)
    }

    override fun publish(channel: String, message: String): CompletionStage<Long> = connection.async().publish(channel, message)

    override fun close() = connection.close()
}
