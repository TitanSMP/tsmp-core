package de.titansmp.core.event

import de.titansmp.api.event.EventListener
import de.titansmp.api.player.event.PlayerConnected
import de.titansmp.core.config.RedisConfig
import de.titansmp.core.redis.LettuceRedisService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Testcontainers(disabledWithoutDocker = true)
class RedisEventBusIntegrationTest {
    @Container
    private val redisContainer = GenericContainer(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379)

    private var redis: LettuceRedisService? = null
    private var subscriber: RedisEventBus? = null
    private var publisher: RedisEventBus? = null

    @AfterEach
    fun tearDown() {
        subscriber?.close()
        publisher?.close()
        redis?.close()
    }

    @Test
    fun `different bus instances exchange event through isolated Redis channel`() {
        redis = LettuceRedisService(RedisConfig(redisContainer.host, redisContainer.getMappedPort(6379), null)).also { it.start() }
        subscriber = RedisEventBus("integration", LettuceRedisPubSubTransport(redis!!.openPubSubConnection()))
        publisher = RedisEventBus("integration", LettuceRedisPubSubTransport(redis!!.openPubSubConnection()))
        val received = CountDownLatch(1)
        val playerId = UUID.randomUUID()
        subscriber!!.subscribe(PlayerConnected.TYPE, EventListener { envelope ->
            assertEquals(playerId, (envelope.event as PlayerConnected).playerId)
            received.countDown()
        })

        publisher!!.publish(
            PlayerConnected(
                de.titansmp.api.event.EventMetadata(eventType = PlayerConnected.TYPE, sourceServerId = "smp-01"),
                playerId,
                "TitanUser",
                "smp-01",
            ),
        ).toCompletableFuture().get(3, TimeUnit.SECONDS)

        org.junit.jupiter.api.Assertions.assertTrue(received.await(3, TimeUnit.SECONDS))
    }
}
