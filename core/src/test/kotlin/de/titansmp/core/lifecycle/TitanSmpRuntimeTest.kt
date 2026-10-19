package de.titansmp.core.lifecycle

import de.titansmp.api.server.ServerEnvironment
import de.titansmp.api.server.ServerIdentity
import de.titansmp.api.server.ServerType
import de.titansmp.api.event.EventBus
import de.titansmp.api.event.EventEnvelope
import de.titansmp.api.event.EventListener
import de.titansmp.api.event.EventSubscription
import de.titansmp.api.event.EventType
import de.titansmp.api.event.NetworkEvent
import de.titansmp.core.config.DatabaseConfig
import de.titansmp.core.config.RedisConfig
import de.titansmp.core.config.TitanSmpConfig
import de.titansmp.core.database.DatabaseService
import de.titansmp.core.redis.RedisService
import io.lettuce.core.api.sync.RedisCommands
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory
import javax.sql.DataSource

class TitanSmpRuntimeTest {
    @Test
    fun `starts configuration database migrations redis then stops in reverse`() {
        val events = mutableListOf<String>()
        val database = FakeDatabaseService(events)
        val redis = FakeRedisService(events)
        val runtime = TitanSmpRuntime(
            configLoader = { events += "config"; testConfig() },
            databaseFactory = { events += "database.create"; database },
            migrationRunner = { events += "migrations" },
            redisFactory = { events += "redis.create"; redis },
            eventBusFactory = { _, _ -> events += "eventBus.create"; FakeEventBus(events) },
            logger = LoggerFactory.getLogger("test-runtime"),
        )

        val server = runtime.start()

        assertEquals(RuntimeState.READY, runtime.state)
        assertEquals("smp-explicit", server.identity.id)
        assertEquals("TitanSMP", server.networkName)
        runtime.close()

        assertEquals(RuntimeState.STOPPED, runtime.state)
        assertEquals(
            listOf(
                "config", "database.create", "database.start", "migrations", "redis.create", "redis.start",
                "eventBus.create", "eventBus.close", "redis.close", "database.close",
            ),
            events,
        )
    }

    @Test
    fun `cleans up database if redis startup fails`() {
        val events = mutableListOf<String>()
        val database = FakeDatabaseService(events)
        val redis = FakeRedisService(events, failStart = true)
        val runtime = TitanSmpRuntime(
            configLoader = { testConfig() },
            databaseFactory = { database },
            migrationRunner = { events += "migrations" },
            redisFactory = { redis },
            logger = LoggerFactory.getLogger("test-runtime-failure"),
        )

        assertFailsWith<RuntimeStartupException> { runtime.start() }

        assertEquals(RuntimeState.STOPPED, runtime.state)
        assertEquals(listOf("database.start", "migrations", "redis.start", "redis.close", "database.close"), events)
    }

    @Test
    fun `does not allow starting twice without shutdown`() {
        val events = mutableListOf<String>()
        val runtime = TitanSmpRuntime(
            configLoader = { testConfig() },
            databaseFactory = { FakeDatabaseService(events) },
            migrationRunner = {},
            redisFactory = { FakeRedisService(events) },
            eventBusFactory = { _, _ -> FakeEventBus(events) },
            logger = LoggerFactory.getLogger("test-runtime-repeat"),
        )

        runtime.start()
        assertFailsWith<IllegalStateException> { runtime.start() }
        runtime.close()
        assertTrue(events.contains("database.close"))
    }

    private fun testConfig() = TitanSmpConfig(
        serverIdentity = ServerIdentity("smp-explicit", ServerType.SMP, ServerEnvironment.DEVELOPMENT),
        networkName = "TitanSMP",
        database = DatabaseConfig("localhost", 3306, "titansmp", "user", "dummy-password", 2),
        redis = RedisConfig("localhost", 6379, null),
    )

    private class FakeDatabaseService(private val events: MutableList<String>) : DatabaseService {
        override val dataSource: DataSource
            get() = error("The test migration runner does not need a real DataSource")

        override fun start() {
            events += "database.start"
        }

        override fun close() {
            events += "database.close"
        }
    }

    private class FakeRedisService(
        private val events: MutableList<String>,
        private val failStart: Boolean = false,
    ) : RedisService {
        override val commands: RedisCommands<String, String>
            get() = error("Commands are not used by lifecycle tests")

        override fun start() {
            events += "redis.start"
            if (failStart) error("synthetic Redis startup failure")
        }

        override fun close() {
            events += "redis.close"
        }
    }

    private class FakeEventBus(private val events: MutableList<String>) : EventBus {
        override fun publish(event: NetworkEvent) = java.util.concurrent.CompletableFuture.completedFuture(Unit)
        override fun subscribe(eventType: EventType, listener: EventListener) = EventSubscription {}
        override fun close() { events += "eventBus.close" }
    }
}
