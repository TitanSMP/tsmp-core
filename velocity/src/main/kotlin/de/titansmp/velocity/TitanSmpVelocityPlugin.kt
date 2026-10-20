package de.titansmp.velocity

import com.google.inject.Inject
import com.velocitypowered.api.event.EventTask
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.ServerConnectedEvent
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.LoginEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.server.ServerRegisteredEvent
import com.velocitypowered.api.event.proxy.server.ServerUnregisteredEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.scheduler.ScheduledTask
import de.titansmp.api.databridge.PlayerTransferFailed
import de.titansmp.api.databridge.PlayerTransferCompleted
import de.titansmp.api.databridge.PlayerTransferPrepare
import de.titansmp.api.databridge.PlayerTransferReady
import de.titansmp.api.databridge.PlayerTransferTargetPrepared
import de.titansmp.api.event.EventBus
import de.titansmp.api.event.EventListener
import de.titansmp.api.event.EventMetadata
import de.titansmp.api.event.EventSubscription
import de.titansmp.api.teleport.TeleportTransferRequested
import de.titansmp.api.servertransfer.ServerTransferResult
import de.titansmp.core.databridge.PlayerTransferRegistry
import de.titansmp.core.display.DefaultPlayerDisplayService
import de.titansmp.core.display.LuckPermsDisplayListener
import de.titansmp.core.display.LuckPermsDisplayProvider
import de.titansmp.core.clans.ClanDisplayRefreshListener
import de.titansmp.core.clans.ClanDisplaySuffixProvider
import de.titansmp.core.clans.ClanValidationConfig
import de.titansmp.core.clans.DefaultClanService
import de.titansmp.core.clans.JdbcClanRepository
import de.titansmp.core.lifecycle.TitanSmpRuntime
import de.titansmp.core.moderation.DefaultModerationService
import de.titansmp.core.moderation.JdbcModerationRepository
import de.titansmp.core.moderation.LuckPermsModerationPermissionService
import de.titansmp.core.servertransfer.DefaultServerTransferService
import de.titansmp.core.servertransfer.LuckPermsQueuePriorityResolver
import de.titansmp.core.servertransfer.RedisQueueStore
import de.titansmp.velocity.display.VelocityTabListModule
import de.titansmp.velocity.servertransfer.VelocityBackendDirectory
import de.titansmp.velocity.servertransfer.VelocityPlayerConnectionGateway
import de.titansmp.velocity.servertransfer.VelocityServerTransferCommands
import de.titansmp.api.moderation.PlayerBanned
import de.titansmp.api.moderation.PlayerKicked
import de.titansmp.api.moderation.PlayerMuted
import de.titansmp.api.moderation.PlayerUnbanned
import de.titansmp.api.moderation.PlayerUnmuted
import net.luckperms.api.LuckPermsProvider
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
    @Volatile private var runtime: TitanSmpRuntime? = null
    @Volatile private var proxyServerId: String = "velocity"
    @Volatile private var timeoutMs: Long = 5_000
    @Volatile private var eventBus: EventBus? = null
    @Volatile private var registry = PlayerTransferRegistry()
    private val pending = ConcurrentHashMap<UUID, PendingTransfer>()
    private val byPlayer = ConcurrentHashMap<UUID, UUID>()
    private val subscriptions = mutableListOf<EventSubscription>()
    @Volatile private var moderation: de.titansmp.api.moderation.ModerationService? = null
    @Volatile private var displayService: DefaultPlayerDisplayService? = null
    private var displayListener: LuckPermsDisplayListener? = null
    private var clanDisplayRefresh: ClanDisplayRefreshListener? = null
    private var tabListModule: VelocityTabListModule? = null
    @Volatile private var serverTransfer: DefaultServerTransferService? = null
    private var backendDirectory: VelocityBackendDirectory? = null
    private val transferTasks = mutableListOf<ScheduledTask>()
    private val moderationExecutor = java.util.concurrent.Executors.newFixedThreadPool(2) { task ->
        Thread(task, "titansmp-velocity-moderation-db").apply { isDaemon = true }
    }

    @Subscribe
    fun onProxyInitialize(event: ProxyInitializeEvent): EventTask = EventTask.async {
        try {
            val started = TitanSmpRuntime()
            val serverIdentity = started.start()
            val bus = started.eventBus()
            runtime = started
            proxyServerId = serverIdentity.identity.id
            timeoutMs = started.playerTransferTimeoutMs
            val luckPerms = LuckPermsProvider.get()
            moderation = DefaultModerationService(
                JdbcModerationRepository(started.databaseDataSource()),
                LuckPermsModerationPermissionService(luckPerms),
                bus,
                moderationExecutor,
                logger = java.util.logging.Logger.getLogger("de.titansmp.velocity.moderation"),
            )
            val displayLogger = java.util.logging.Logger.getLogger("de.titansmp.velocity.display")
            val clanConfig = started.clansConfig
            val clans = if (clanConfig.enabled) DefaultClanService(
                JdbcClanRepository(started.databaseDataSource()),bus,moderationExecutor,proxyServerId,
                ClanValidationConfig(clanConfig.pageSize,clanConfig.invitationExpirationHours,clanConfig.nameMinLength,clanConfig.nameMaxLength,clanConfig.tagMinLength,clanConfig.tagMaxLength),
            ) else null
            val display = DefaultPlayerDisplayService(
                LuckPermsDisplayProvider(luckPerms),
                suffixProviders = listOfNotNull(clans?.let(::ClanDisplaySuffixProvider)),
                eventBus = bus,
                serverId = proxyServerId,
                logger = displayLogger,
            )
            displayService = display
            clanDisplayRefresh = if (clans != null) ClanDisplayRefreshListener(bus,display) else null
            displayListener = LuckPermsDisplayListener(luckPerms, display, displayLogger)
            tabListModule = VelocityTabListModule(this, proxy, display, bus, logger).also { proxy.eventManager.register(this, it) }
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
            subscriptions += bus.subscribe(TeleportTransferRequested.TYPE, EventListener(::onTeleportTransferRequested))
            subscriptions += bus.subscribe(PlayerBanned.TYPE, EventListener(::onModerationEvent))
            subscriptions += bus.subscribe(PlayerUnbanned.TYPE, EventListener(::onModerationEvent))
            subscriptions += bus.subscribe(PlayerMuted.TYPE, EventListener(::onModerationEvent))
            subscriptions += bus.subscribe(PlayerUnmuted.TYPE, EventListener(::onModerationEvent))
            subscriptions += bus.subscribe(PlayerKicked.TYPE, EventListener(::onModerationEvent))
            val transferConfig = started.serverTransferConfig
            if (transferConfig.enabled) {
                val directory = VelocityBackendDirectory(proxy, transferConfig, logger)
                val service = DefaultServerTransferService(
                    config = transferConfig,
                    directory = directory,
                    gateway = VelocityPlayerConnectionGateway(proxy),
                    queueStore = RedisQueueStore(started.redisService(), started.networkId),
                    priorityResolver = LuckPermsQueuePriorityResolver(luckPerms, transferConfig.defaultPriority),
                    eventBus = bus,
                    serverId = proxyServerId,
                )
                backendDirectory = directory
                serverTransfer = service
                VelocityServerTransferCommands(proxy, this, service, transferConfig, logger).register()
                directory.refresh().whenComplete { _, failure ->
                    if (failure != null) logger.warn("Initial backend availability refresh failed", failure)
                }
                transferTasks += proxy.scheduler.buildTask(this, Runnable { service.processQueues() })
                    .delay(transferConfig.processingIntervalMs, TimeUnit.MILLISECONDS)
                    .repeat(transferConfig.processingIntervalMs, TimeUnit.MILLISECONDS)
                    .schedule()
                transferTasks += proxy.scheduler.buildTask(this, Runnable { directory.refresh() })
                    .delay(5, TimeUnit.SECONDS)
                    .repeat(10, TimeUnit.SECONDS)
                    .schedule()
                logger.info(
                    "TitanSMP server transfer queues ready; types={} backends={}",
                    transferConfig.enabledTypes(),
                    transferConfig.backends.keys.sorted(),
                )
            } else {
                logger.info("TitanSMP server transfer queues are disabled")
            }
            logger.info("TitanSMP Velocity transfer coordinator ready as {}", proxyServerId)
        } catch (failure: Throwable) {
            subscriptions.forEach(EventSubscription::close)
            subscriptions.clear()
            cancelTransferTasks()
            try { serverTransfer?.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
            serverTransfer = null
            backendDirectory = null
            try { tabListModule?.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
            tabListModule = null
            try { displayListener?.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
            displayListener = null
            try { clanDisplayRefresh?.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
            clanDisplayRefresh = null
            try { displayService?.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
            displayService = null
            try { runtime?.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
            runtime = null
            eventBus = null
            moderation = null
            logger.error("TitanSMP Velocity failed to start", failure)
            throw failure
        }
    }

    /** A current persisted ban is checked before Velocity starts any backend connection. */
    @Subscribe
    fun onLogin(event: LoginEvent): EventTask {
        val service = moderation ?: return EventTask.async { event.setResult(com.velocitypowered.api.event.ResultedEvent.ComponentResult.denied(net.kyori.adventure.text.Component.text("Network moderation checks are unavailable."))) }
        return EventTask.resumeWhenComplete(service.checkPunishment(event.player.uniqueId, de.titansmp.api.moderation.PunishmentType.BAN).toCompletableFuture().handle { punishment, failure ->
            when {
                failure != null -> {
                    logger.error("Could not check active ban for {}", event.player.uniqueId, failure)
                    event.setResult(com.velocitypowered.api.event.ResultedEvent.ComponentResult.denied(net.kyori.adventure.text.Component.text("Player data is temporarily unavailable. Please try again.")))
                }
                punishment != null -> event.setResult(com.velocitypowered.api.event.ResultedEvent.ComponentResult.denied(net.kyori.adventure.text.Component.text("You are banned: ${punishment.reason}")))
            }
            Unit
        })
    }

    private fun onModerationEvent(envelope: de.titansmp.api.event.EventEnvelope) {
        val event = envelope.event
        val targetId = when (event) {
            is PlayerBanned -> event.targetId
            is PlayerKicked -> event.targetId
            is PlayerMuted -> event.targetId
            is PlayerUnmuted -> event.targetId
            is PlayerUnbanned -> return
            else -> return
        }
        val reason = when (event) {
            is PlayerBanned -> "You are banned: ${event.reason}"
            is PlayerKicked -> event.reason
            else -> return
        }
        proxy.getPlayer(targetId).ifPresent { it.disconnect(net.kyori.adventure.text.Component.text(reason)) }
    }

    @Subscribe
    fun onServerConnected(event: ServerConnectedEvent) {
        serverTransfer?.playerConnected(event.player.uniqueId, event.server.serverInfo.name)
        backendDirectory?.refresh()
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        serverTransfer?.playerDisconnected(event.player.uniqueId)
    }

    @Subscribe
    fun onServerRegistered(event: ServerRegisteredEvent) {
        backendDirectory?.refresh()
        serverTransfer?.processQueues()
    }

    @Subscribe
    fun onServerUnregistered(event: ServerUnregisteredEvent) {
        backendDirectory?.refresh()
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

    private fun onTeleportTransferRequested(envelope: de.titansmp.api.event.EventEnvelope) {
        val event = envelope.event as? TeleportTransferRequested ?: return
        val player = proxy.getPlayer(event.playerUuid).orElse(null) ?: return
        val service = serverTransfer
        if(service==null) {
            clearTeleportState(event)
            player.sendMessage(net.kyori.adventure.text.Component.text("Teleport transfer is unavailable."))
            return
        }
        val transfer=event.targetServerId?.let { service.transferToServer(event.playerUuid,it) }
            ?: service.requestJoin(event.playerUuid,requireNotNull(event.targetServerType))
        transfer.whenComplete { result, failure ->
            if(failure!=null || result is ServerTransferResult.Failed) {
                clearTeleportState(event)
                player.sendMessage(net.kyori.adventure.text.Component.text("Teleport transfer could not be completed."))
            }
        }
    }

    private fun clearTeleportState(event:TeleportTransferRequested) {
        val redis=runtime?.redisService()?.commands ?: return
        redis.del("titansmp:teleport:pending:${event.playerUuid}")
        val normalizedScope=event.cooldownScope?.trim()?.lowercase() ?: "-"
        redis.del("titansmp:teleport:cooldown:${event.cooldownOwnerUuid}:${event.cooldownCategory.name}:$normalizedScope")
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
        cancelTransferTasks()
        try { serverTransfer?.close() } catch (failure: Exception) { logger.error("TitanSMP server transfer shutdown failed", failure) }
        serverTransfer = null
        backendDirectory = null
        pending.values.toList().forEach { fail(it, eventBus, "proxy-shutdown") }
        try { tabListModule?.close() } catch (failure: Exception) { logger.error("TitanSMP Velocity TAB shutdown failed", failure) }
        tabListModule = null
        try { displayListener?.close() } catch (failure: Exception) { logger.error("TitanSMP Velocity display shutdown failed", failure) }
        displayListener = null
        try { clanDisplayRefresh?.close() } catch (failure: Exception) { logger.error("TitanSMP Velocity clan display shutdown failed", failure) }
        clanDisplayRefresh = null
        try { displayService?.close() } catch (failure: Exception) { logger.error("TitanSMP Velocity display shutdown failed", failure) }
        displayService = null
        try { runtime?.close() } catch (failure: Exception) { logger.error("TitanSMP Velocity shutdown failed", failure) }
        runtime = null
        moderation = null
        moderationExecutor.shutdown()
        eventBus = null
    }

    private fun cancelTransferTasks() {
        transferTasks.forEach { task ->
            try { task.cancel() } catch (failure: Exception) { logger.debug("Could not cancel transfer task", failure) }
        }
        transferTasks.clear()
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
