package de.titansmp.velocity

import com.google.inject.Inject
import com.velocitypowered.api.event.EventTask
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.ProxyServer
import de.titansmp.api.databridge.PlayerTransferFailed
import de.titansmp.api.databridge.PlayerTransferCompleted
import de.titansmp.api.databridge.PlayerTransferPrepare
import de.titansmp.api.databridge.PlayerTransferReady
import de.titansmp.api.databridge.PlayerTransferTargetPrepared
import de.titansmp.api.event.EventBus
import de.titansmp.api.event.EventListener
import de.titansmp.api.event.EventMetadata
import de.titansmp.api.event.EventSubscription
import de.titansmp.core.databridge.PlayerTransferRegistry
import de.titansmp.core.lifecycle.TitanSmpEventRuntime
import org.slf4j.Logger
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Coordinates backend-to-backend connections after the source has committed its player state. */
@Plugin(id = "titansmp-velocity", name = "TitanSMP", version = "${'$'}{project.version}")
class TitanSmpVelocityPlugin @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
) {
    @Volatile private var runtime: TitanSmpEventRuntime? = null
    @Volatile private var proxyServerId: String = "velocity"
    @Volatile private var timeoutMs: Long = 5_000
    @Volatile private var eventBus: EventBus? = null
    @Volatile private var registry = PlayerTransferRegistry()
    private val pending = ConcurrentHashMap<UUID, PendingTransfer>()
    private val byPlayer = ConcurrentHashMap<UUID, UUID>()
    private val subscriptions = mutableListOf<EventSubscription>()

    @Subscribe
    fun onProxyInitialize(event: ProxyInitializeEvent): EventTask = EventTask.async {
        try {
            val started = TitanSmpEventRuntime()
            val bus = started.start()
            runtime = started
            proxyServerId = started.serverId
            timeoutMs = started.playerTransferTimeoutMs
            registry = PlayerTransferRegistry(cancelPublisher = { transfer, reason ->
                bus.publish(PlayerTransferFailed(
                    EventMetadata(eventType = PlayerTransferFailed.TYPE, sourceServerId = proxyServerId),
                    transfer.transferId, transfer.playerUuid, transfer.sourceServerId, transfer.targetServerId, reason, Instant.now(),
                ))
            })
            eventBus = bus
            subscriptions += bus.subscribe(PlayerTransferReady.TYPE, EventListener(::onTransferEvent))
            subscriptions += bus.subscribe(PlayerTransferTargetPrepared.TYPE, EventListener(::onTransferEvent))
            subscriptions += bus.subscribe(PlayerTransferFailed.TYPE, EventListener(::onTransferEvent))
            subscriptions += bus.subscribe(PlayerTransferCompleted.TYPE, EventListener(::onTransferEvent))
            logger.info("TitanSMP Velocity transfer coordinator ready as {}", proxyServerId)
        } catch (failure: Throwable) {
            logger.error("TitanSMP Velocity failed to start", failure)
            throw failure
        }
    }

    @Subscribe
    fun onServerPreConnect(event: ServerPreConnectEvent): EventTask? {
        if (!event.result.isAllowed) return null
        val previous = event.previousServer ?: return null // Initial joins use the normal Paper load path.
        val bus = eventBus ?: run {
            event.setResult(ServerPreConnectEvent.ServerResult.denied())
            return null
        }
        val playerId = event.player.uniqueId
        val source = previous.serverInfo.name
        val target = event.originalServer.serverInfo.name
        if (source == target) return null
        if (byPlayer.containsKey(playerId)) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied())
            event.player.sendMessage(net.kyori.adventure.text.Component.text("A server transfer is already in progress. Please wait."))
            return null
        }
        val transfer = registry.begin(playerId, source, target) ?: run {
            event.setResult(ServerPreConnectEvent.ServerResult.denied())
            return null
        }
        val future = CompletableFuture<Boolean>()
        val context = PendingTransfer(transfer.transferId, playerId, source, target, future)
        if (pending.putIfAbsent(transfer.transferId, context) != null || byPlayer.putIfAbsent(playerId, transfer.transferId) != null) {
            registry.fail(transfer.transferId, playerId)
            pending.remove(transfer.transferId)
            event.setResult(ServerPreConnectEvent.ServerResult.denied())
            return null
        }

        val prepare = PlayerTransferPrepare(
            EventMetadata(eventType = PlayerTransferPrepare.TYPE, sourceServerId = proxyServerId),
            transfer.transferId, playerId, source, target, Instant.now(),
        )
        bus.publish(prepare).whenComplete { _, failure ->
            if (failure != null) fail(context, bus, "prepare-publish-failed")
        }
        CompletableFuture.delayedExecutor(timeoutMs, TimeUnit.MILLISECONDS).execute {
            if (!future.isDone) fail(context, bus, "source-prepare-timeout")
        }

        return EventTask.resumeWhenComplete(future.thenApply { ready ->
            if (ready) {
                event.setResult(ServerPreConnectEvent.ServerResult.allowed(event.originalServer))
            } else {
                event.setResult(ServerPreConnectEvent.ServerResult.denied())
                event.player.sendMessage(net.kyori.adventure.text.Component.text("Your player data could not be saved. The transfer was cancelled."))
            }
            Unit
        })
    }

    private fun onTransferEvent(envelope: de.titansmp.api.event.EventEnvelope) {
        when (val event = envelope.event) {
            is PlayerTransferReady -> {
                val context = pending[event.transferId] ?: return
                if (context.playerUuid != event.playerUuid || context.sourceServerId != event.sourceServerId || context.targetServerId != event.targetServerId) return
                context.expectedRevision.compareAndSet(-1, event.stateRevision)
                activateWhenPrepared(context)
            }
            is PlayerTransferTargetPrepared -> {
                val context = pending[event.transferId] ?: return
                if (context.playerUuid != event.playerUuid || context.sourceServerId != event.sourceServerId || context.targetServerId != event.targetServerId) return
                context.targetPrepared.set(true)
                activateWhenPrepared(context)
            }
            is PlayerTransferFailed -> {
                val context = pending[event.transferId] ?: return
                if (context.playerUuid == event.playerUuid && context.sourceServerId == event.sourceServerId && context.targetServerId == event.targetServerId) {
                    fail(context, eventBus, event.reason)
                }
            }
            is PlayerTransferCompleted -> {
                val context = pending[event.transferId] ?: return
                if (context.playerUuid == event.playerUuid && context.sourceServerId == event.sourceServerId && context.targetServerId == event.targetServerId &&
                    registry.complete(event.transferId, event.playerUuid, event.stateRevision)) {
                    pending.remove(event.transferId, context)
                    byPlayer.remove(event.playerUuid, event.transferId)
                }
            }
            else -> Unit
        }
    }

    private fun activateWhenPrepared(context: PendingTransfer) {
        val revision = context.expectedRevision.get()
        if (!context.targetPrepared.get() || revision < 0 || !context.activated.compareAndSet(false, true)) return
        if (registry.ready(context.transferId, context.playerUuid, context.sourceServerId, context.targetServerId, revision) == null) {
            context.activated.set(false)
            return
        }
        registry.connecting(context.transferId)
        context.future.complete(true)
        CompletableFuture.delayedExecutor(timeoutMs * 2, TimeUnit.MILLISECONDS).execute {
            if (registry.find(context.transferId)?.state == de.titansmp.api.databridge.PlayerTransferState.CONNECTING) {
                fail(context, eventBus, "target-load-timeout")
            }
        }
    }

    private fun fail(context: PendingTransfer, bus: EventBus?, reason: String) {
        val changed = registry.fail(context.transferId, context.playerUuid)
        if (!changed) return
        context.future.complete(false)
        byPlayer.remove(context.playerUuid, context.transferId)
        if (bus != null) {
            val failed = PlayerTransferFailed(
                EventMetadata(eventType = PlayerTransferFailed.TYPE, sourceServerId = proxyServerId),
                context.transferId, context.playerUuid, context.sourceServerId, context.targetServerId, reason, Instant.now(),
            )
            bus.publish(failed).whenComplete { _, failure -> if (failure != null) logger.error("Could not publish failed transfer {}", context.transferId, failure) }
        }
        logger.warn("Player transfer {} failed: {}", context.transferId, reason)
        pending.remove(context.transferId, context)
    }

    @Subscribe
    fun onProxyShutdown(event: com.velocitypowered.api.event.proxy.ProxyShutdownEvent) {
        subscriptions.forEach(EventSubscription::close)
        subscriptions.clear()
        pending.values.toList().forEach { fail(it, eventBus, "proxy-shutdown") }
        try { runtime?.close() } catch (failure: Exception) { logger.error("TitanSMP Velocity shutdown failed", failure) }
        runtime = null
        eventBus = null
    }

    private data class PendingTransfer(
        val transferId: UUID,
        val playerUuid: UUID,
        val sourceServerId: String,
        val targetServerId: String,
        val future: CompletableFuture<Boolean>,
        val targetPrepared: AtomicBoolean = AtomicBoolean(),
        val activated: AtomicBoolean = AtomicBoolean(),
        val expectedRevision: AtomicLong = AtomicLong(-1),
    )
}
