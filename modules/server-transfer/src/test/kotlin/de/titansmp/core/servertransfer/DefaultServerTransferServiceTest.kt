package de.titansmp.core.servertransfer

import de.titansmp.api.servertransfer.ServerQueueLeaveReason
import de.titansmp.api.servertransfer.ServerQueueLeft
import de.titansmp.api.servertransfer.ServerQueueTransferCompleted
import de.titansmp.api.servertransfer.ServerQueueTransferFailed
import de.titansmp.api.servertransfer.ServerQueueTransferStarted
import de.titansmp.api.servertransfer.ServerTransferFailure
import de.titansmp.api.servertransfer.ServerTransferResult
import de.titansmp.core.config.ServerTransferBackendConfig
import de.titansmp.core.config.ServerTransferConfig
import de.titansmp.core.config.ServerTransferTypeConfig
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DefaultServerTransferServiceTest {
    private val now: Instant = Instant.parse("2026-10-20T12:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val player: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000aa")

    private val config = ServerTransferConfig(
        enabled = true,
        defaultPriority = 0,
        reservationTimeoutMs = 5_000,
        aliases = mapOf("lobby" to "lobby", "hub" to "lobby"),
        types = mapOf(
            "lobby" to ServerTransferTypeConfig(enabled = true, maxPlayers = 100),
            "smp" to ServerTransferTypeConfig(enabled = true, maxPlayers = 50),
        ),
        backends = mapOf(
            "lobby-01" to ServerTransferBackendConfig("lobby"),
            "lobby-02" to ServerTransferBackendConfig("lobby"),
            "smp-01" to ServerTransferBackendConfig("smp"),
        ),
    )

    @Test
    fun `chooses the least populated eligible backend and delegates to the connection gateway`() {
        val gateway = FakeGateway(currentServer = "smp-01")
        val directory = FakeDirectory(
            listOf(
                backend("lobby-01", "lobby", 72),
                backend("lobby-02", "lobby", 51),
                backend("smp-01", "smp", 10),
            ),
        )
        val service = service(directory, gateway)

        val result = service.requestJoin(player, "lobby").toCompletableFuture().join()

        assertIs<ServerTransferResult.Connected>(result)
        assertEquals("lobby-02", result.serverId)
        // A direct transfer must go through the existing gateway, never a second protocol.
        assertEquals(listOf("lobby-02"), gateway.connections)
    }

    @Test
    fun `queues when every backend of the type is full`() {
        val service = service(
            FakeDirectory(listOf(backend("lobby-01", "lobby", 100), backend("lobby-02", "lobby", 100))),
            FakeGateway(currentServer = "smp-01"),
        )

        val result = service.requestJoin(player, "lobby").toCompletableFuture().join()

        assertIs<ServerTransferResult.Queued>(result)
        assertEquals(1, result.position)
        assertEquals(1, result.size)
        assertTrue(service.queueStatus(player).toCompletableFuture().join() != null)
    }

    @Test
    fun `unavailable and full backends are excluded from direct joining`() {
        val gateway = FakeGateway(currentServer = "smp-01")
        val service = service(
            FakeDirectory(
                listOf(
                    backend("lobby-01", "lobby", 0, available = false),
                    backend("lobby-02", "lobby", 100),
                ),
            ),
            gateway,
        )

        val result = service.requestJoin(player, "lobby").toCompletableFuture().join()

        assertIs<ServerTransferResult.Queued>(result)
        assertTrue(gateway.connections.isEmpty())
    }

    @Test
    fun `same server type does not transfer the player`() {
        val gateway = FakeGateway(currentServer = "lobby-01")
        val service = service(FakeDirectory(listOf(backend("lobby-01", "lobby", 10))), gateway)

        val result = service.requestJoin(player, "lobby").toCompletableFuture().join()

        assertIs<ServerTransferResult.AlreadyConnected>(result)
        assertTrue(gateway.connections.isEmpty())
    }

    @Test
    fun `unknown server type is rejected`() {
        val service = service(FakeDirectory(emptyList()), FakeGateway(currentServer = "smp-01"))

        val result = service.requestJoin(player, "lobby-01").toCompletableFuture().join()

        assertIs<ServerTransferResult.Failed>(result)
        assertEquals(ServerTransferFailure.UNKNOWN_SERVER_TYPE, result.reason)
    }

    @Test
    fun `queue allocation reserves then delegates to the existing connection gateway`() {
        val gateway = FakeGateway(currentServer = "smp-01")
        val eventBus = FakeEventBus()
        val directory = FakeDirectory(listOf(backend("lobby-01", "lobby", 100)))
        val service = service(directory, gateway, eventBus)

        service.requestJoin(player, "lobby").toCompletableFuture().join()
        directory.backends = listOf(backend("lobby-01", "lobby", 99))
        service.processQueues()

        assertEquals(listOf("lobby-01"), gateway.connections)
        assertTrue(eventBus.events.any { it is ServerQueueTransferStarted })
        assertTrue(eventBus.events.any { it is ServerQueueTransferCompleted })
        assertTrue(eventBus.events.any { it is ServerQueueLeft && it.reason == ServerQueueLeaveReason.TRANSFERRED })
        assertEquals(null, service.queueStatus(player).toCompletableFuture().join())
    }

    @Test
    fun `failed queued transfer keeps the player in the queue`() {
        val gateway = FakeGateway(currentServer = "smp-01", connectResult = false)
        val eventBus = FakeEventBus()
        val directory = FakeDirectory(listOf(backend("lobby-01", "lobby", 100)))
        val service = service(directory, gateway, eventBus)

        service.requestJoin(player, "lobby").toCompletableFuture().join()
        directory.backends = listOf(backend("lobby-01", "lobby", 99))
        service.processQueues()

        assertTrue(eventBus.events.any { it is ServerQueueTransferFailed })
        val status = service.queueStatus(player).toCompletableFuture().join()
        assertEquals(1, status?.position)
        assertEquals(1, status?.size)
    }

    @Test
    fun `manual server change clears a stale queue entry`() {
        val service = service(FakeDirectory(listOf(backend("lobby-01", "lobby", 100))), FakeGateway(currentServer = "smp-01"))

        service.requestJoin(player, "lobby").toCompletableFuture().join()
        service.playerConnected(player, "lobby-01")

        assertEquals(null, service.queueStatus(player).toCompletableFuture().join())
    }

    @Test
    fun `disconnect removes the queued player idempotently`() {
        val service = service(FakeDirectory(listOf(backend("lobby-01", "lobby", 100))), FakeGateway(currentServer = "smp-01"))

        service.requestJoin(player, "lobby").toCompletableFuture().join()
        service.playerDisconnected(player)
        service.playerDisconnected(player)

        assertEquals(null, service.queueStatus(player).toCompletableFuture().join())
    }

    @Test
    fun `hub and lobby aliases resolve to the same configured type`() {
        assertEquals("lobby", ServerTransferAliases.resolve(config, "lobby"))
        assertEquals("lobby", ServerTransferAliases.resolve(config, "hub"))
        assertEquals(null, ServerTransferAliases.resolve(config, "missing"))
        assertEquals("Queue position: 5 / 20", QueueActionBarFormatter.format(5, 20))
    }

    @Test
    fun `priority resolver controls queue ordering`() {
        val low = UUID.fromString("00000000-0000-0000-0000-0000000000b1")
        val high = UUID.fromString("00000000-0000-0000-0000-0000000000b2")
        val gateway = FakeGateway(currentServer = "smp-01")
        val service = service(
            FakeDirectory(listOf(backend("lobby-01", "lobby", 100))),
            gateway,
            FakeEventBus(),
            QueuePriorityResolver { uuid -> if (uuid == high) 3 else 1 },
        )

        service.requestJoin(low, "lobby").toCompletableFuture().join()
        service.requestJoin(high, "lobby").toCompletableFuture().join()

        assertEquals(1, service.queueStatus(high).toCompletableFuture().join()?.position)
        assertEquals(2, service.queueStatus(low).toCompletableFuture().join()?.position)
    }

    private fun backend(name: String, type: String, players: Int, available: Boolean = true) =
        BackendSnapshot(name, type, players, 100, available, true)

    private fun service(
        directory: FakeDirectory,
        gateway: FakeGateway,
        eventBus: FakeEventBus = FakeEventBus(),
        priorityResolver: QueuePriorityResolver = QueuePriorityResolver { 0 },
    ) = DefaultServerTransferService(
        config = config,
        directory = directory,
        gateway = gateway,
        queueStore = InMemoryQueueStore(),
        priorityResolver = priorityResolver,
        eventBus = eventBus,
        serverId = "velocity-01",
        clock = clock,
    )

    private class FakeDirectory(var backends: List<BackendSnapshot>) : BackendDirectory {
        override fun snapshot(): List<BackendSnapshot> = backends
        override fun typeForBackend(backendName: String): String? = when (backendName) {
            "lobby-01", "lobby-02" -> "lobby"
            "smp-01" -> "smp"
            else -> null
        }
    }

    private class FakeGateway(
        var currentServer: String?,
        private val connectResult: Boolean = true,
    ) : PlayerConnectionGateway {
        val connections = mutableListOf<String>()

        override fun currentServer(playerUuid: UUID): String? = currentServer
        override fun isOnline(playerUuid: UUID): Boolean = currentServer != null
        override fun connect(playerUuid: UUID, targetServerId: String): CompletionStage<Boolean> {
            connections += targetServerId
            currentServer = targetServerId
            return CompletableFuture.completedFuture(connectResult)
        }
    }
}
