package de.titansmp.paper.databridge

import de.titansmp.api.databridge.PlayerDataService
import de.titansmp.api.databridge.PlayerState
import de.titansmp.api.databridge.PlayerStateUpdated
import de.titansmp.api.databridge.PlayerTransferPrepare
import de.titansmp.api.databridge.PlayerTransferReady
import de.titansmp.api.databridge.PlayerTransferFailed
import de.titansmp.api.databridge.PlayerTransferCompleted
import de.titansmp.api.databridge.PlayerTransferTargetPrepared
import de.titansmp.api.event.EventEnvelope
import de.titansmp.api.event.EventListener
import de.titansmp.api.event.EventSubscription
import de.titansmp.api.databridge.SerializedPlayerItem
import de.titansmp.api.event.EventBus
import de.titansmp.api.event.EventMetadata
import de.titansmp.paper.serialization.PaperItemStackCodec
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityRegainHealthEvent
import org.bukkit.event.player.PlayerExpChangeEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerAttemptPickupItemEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.logging.Logger

/** Paper lifecycle and ItemStack adapter. All Bukkit access stays on Paper's main thread. */
class PlayerDataPaperModule(
    private val plugin: JavaPlugin,
    private val service: PlayerDataService,
    private val serverId: String,
    private val eventBus: EventBus,
    private val logger: Logger = plugin.logger,
    private val io: ExecutorService = Executors.newSingleThreadExecutor { task -> Thread(task, "titansmp-player-state-db").apply { isDaemon = true } },
    private val transferTimeoutMs: Long = 5_000,
) : Listener {
    private val loading = ConcurrentHashMap.newKeySet<UUID>()
    private val states = ConcurrentHashMap<UUID, PlayerState>()
    private val saves = ConcurrentHashMap.newKeySet<CompletableFuture<*>>()
    private val incoming = ConcurrentHashMap<UUID, PlayerTransferPrepare>()
    private val ready = ConcurrentHashMap<UUID, PlayerTransferReady>()
    private val subscriptions = mutableListOf<EventSubscription>()
    private val sourceTransfers = ConcurrentHashMap<UUID, UUID>()
    private val seenTransferIds = ConcurrentHashMap.newKeySet<UUID>()
    @Volatile private var accepting = true

    fun register() {
        Bukkit.getPluginManager().registerEvents(this, plugin)
        subscriptions += eventBus.subscribe(PlayerTransferPrepare.TYPE, EventListener(::onTransferEvent))
        subscriptions += eventBus.subscribe(PlayerTransferReady.TYPE, EventListener(::onTransferEvent))
        subscriptions += eventBus.subscribe(PlayerTransferTargetPrepared.TYPE, EventListener(::onTransferEvent))
        subscriptions += eventBus.subscribe(PlayerTransferFailed.TYPE, EventListener(::onTransferEvent))
        subscriptions += eventBus.subscribe(PlayerTransferCompleted.TYPE, EventListener(::onTransferEvent))
        Bukkit.getOnlinePlayers().forEach { loading += it.uniqueId }
    }

    private fun onTransferEvent(envelope: EventEnvelope) {
        when (val event = envelope.event) {
            is PlayerTransferPrepare -> {
                if (!seenTransferIds.add(event.transferId)) return
                CompletableFuture.delayedExecutor(transferTimeoutMs * 4, java.util.concurrent.TimeUnit.MILLISECONDS).execute { seenTransferIds.remove(event.transferId) }
                if (event.targetServerId == serverId) {
                    incoming.compute(event.playerUuid) { _, previous ->
                        if (previous == null || event.timestamp.isAfter(previous.timestamp)) event else previous
                    }
                    val activePrepare = incoming[event.playerUuid]
                    if (activePrepare?.transferId == event.transferId && activePrepare.sourceServerId == event.sourceServerId && activePrepare.targetServerId == serverId) {
                        eventBus.publish(PlayerTransferTargetPrepared(EventMetadata(eventType = PlayerTransferTargetPrepared.TYPE, sourceServerId = serverId),
                            event.transferId, event.playerUuid, event.sourceServerId, serverId, Instant.now()))
                            .whenComplete { _, failure -> if (failure != null) logger.severe("Could not confirm target for transfer ${event.transferId}: ${failure.message}") }
                    }
                    ready[event.playerUuid]?.takeIf { it.transferId != activePrepare?.transferId }?.let { ready.remove(event.playerUuid, it) }
                    ready[event.playerUuid]?.takeIf { it.transferId == activePrepare?.transferId && it.sourceServerId == activePrepare.sourceServerId }?.let {
                        onMain { Bukkit.getPlayer(event.playerUuid)?.let { player -> loadOnJoin(player) } }
                    }
                    CompletableFuture.delayedExecutor(transferTimeoutMs * 4, java.util.concurrent.TimeUnit.MILLISECONDS).execute {
                        if (incoming.remove(event.playerUuid, event)) {
                            ready[event.playerUuid]?.takeIf { it.transferId == event.transferId }?.let { ready.remove(event.playerUuid, it) }
                            onMain { Bukkit.getPlayer(event.playerUuid)?.let { player ->
                                if (player.uniqueId in loading) player.kick(net.kyori.adventure.text.Component.text("Your transfer could not be prepared. Please try again."))
                            } }
                        }
                    }
                }
                if (event.sourceServerId == serverId) prepareSourceTransfer(event)
            }
            is PlayerTransferReady -> {
                if (event.targetServerId == serverId) {
                    val prepare = incoming[event.playerUuid]
                    if (prepare == null) {
                        ready.putIfAbsent(event.playerUuid, event)
                    } else if (prepare.transferId == event.transferId && prepare.sourceServerId == event.sourceServerId && prepare.targetServerId == event.targetServerId) {
                        if (ready.putIfAbsent(event.playerUuid, event) == null) onMain { Bukkit.getPlayer(event.playerUuid)?.let { player -> loadOnJoin(player) } }
                    }
                }
            }
            is PlayerTransferTargetPrepared -> Unit // Target preparation events are consumed only by Velocity.
            is PlayerTransferFailed -> {
                if (event.targetServerId == serverId) onMain {
                    val prepare = incoming[event.playerUuid]
                    if (prepare?.transferId == event.transferId) {
                        incoming.remove(event.playerUuid, prepare)
                        ready[event.playerUuid]?.takeIf { it.transferId == event.transferId }?.let { ready.remove(event.playerUuid, it) }
                        val player = Bukkit.getPlayer(event.playerUuid)
                        if (player != null && event.playerUuid in loading) {
                            loading -= event.playerUuid
                            player.kick(net.kyori.adventure.text.Component.text("Your server transfer could not be completed. Please reconnect."))
                        }
                    }
                }
                if (event.sourceServerId == serverId) onMain {
                    if (sourceTransfers.remove(event.playerUuid, event.transferId)) {
                        loading -= event.playerUuid
                        Bukkit.getPlayer(event.playerUuid)?.sendMessage(net.kyori.adventure.text.Component.text("Transfer failed; you remain on this server."))
                    }
                }
            }
            is PlayerTransferCompleted -> {
                if (event.targetServerId == serverId) {
                    incoming[event.playerUuid]?.takeIf { it.transferId == event.transferId }?.let { incoming.remove(event.playerUuid, it) }
                    ready[event.playerUuid]?.takeIf { it.transferId == event.transferId }?.let { ready.remove(event.playerUuid, it) }
                }
            }
        }
    }

    private fun prepareSourceTransfer(event: PlayerTransferPrepare) = onMain {
        if (sourceTransfers.putIfAbsent(event.playerUuid, event.transferId) != null) {
            publishTransferFailure(event, "another-transfer-is-active")
            return@onMain
        }
        val player = Bukkit.getPlayer(event.playerUuid)
        if (player == null || !player.isOnline || !accepting) {
            sourceTransfers.remove(event.playerUuid, event.transferId)
            publishTransferFailure(event, "source-player-unavailable")
            return@onMain
        }
        if (!loading.add(event.playerUuid)) {
            sourceTransfers.remove(event.playerUuid, event.transferId)
            publishTransferFailure(event, "source-player-not-ready")
            return@onMain
        }
        val baseline = states[event.playerUuid]
        if (baseline == null) {
            sourceTransfers.remove(event.playerUuid, event.transferId)
            loading -= event.playerUuid
            publishTransferFailure(event, "source-state-not-loaded")
            return@onMain
        }
        val next = try { snapshot(player, baseline.revision) } catch (failure: Throwable) {
            loading -= event.playerUuid
            sourceTransfers.remove(event.playerUuid, event.transferId)
            logger.severe("Could not capture state for transfer ${event.transferId}: ${failure.message}")
            publishTransferFailure(event, "state-capture-failed")
            return@onMain
        }
        val save = if (sameState(baseline, next) && baseline.revision > 0) CompletableFuture.completedFuture(baseline)
            else service.save(next, serverId).toCompletableFuture()
        track(save)
        save.whenComplete { saved, failure -> onMain {
            if (failure != null) {
                loading -= event.playerUuid
                sourceTransfers.remove(event.playerUuid, event.transferId)
                logger.severe("Could not save state for transfer ${event.transferId}: ${failure.cause?.message ?: failure.message}")
                Bukkit.getPlayer(event.playerUuid)?.sendMessage(net.kyori.adventure.text.Component.text("Your data could not be saved, so the transfer was cancelled."))
                publishTransferFailure(event, "state-save-failed")
            } else {
                if (sourceTransfers[event.playerUuid] != event.transferId) return@onMain
                states[event.playerUuid] = saved
                eventBus.publish(PlayerTransferReady(
                    EventMetadata(eventType = PlayerTransferReady.TYPE, sourceServerId = serverId), event.transferId,
                    event.playerUuid, event.sourceServerId, event.targetServerId, saved.revision, Instant.now(),
                )).whenComplete { _, publishFailure -> if (publishFailure != null) {
                    loading -= event.playerUuid
                    sourceTransfers.remove(event.playerUuid, event.transferId)
                    logger.severe("Could not publish ready for transfer ${event.transferId}: ${publishFailure.message}")
                } }
                CompletableFuture.delayedExecutor(transferTimeoutMs * 3, java.util.concurrent.TimeUnit.MILLISECONDS).execute {
                    if (sourceTransfers.remove(event.playerUuid, event.transferId)) onMain {
                        loading -= event.playerUuid
                        Bukkit.getPlayer(event.playerUuid)?.sendMessage(net.kyori.adventure.text.Component.text("Transfer timed out; you remain on this server."))
                    }
                }
            }
        } }
    }

    private fun publishTransferFailure(event: PlayerTransferPrepare, reason: String) {
        eventBus.publish(PlayerTransferFailed(EventMetadata(eventType = PlayerTransferFailed.TYPE, sourceServerId = serverId), event.transferId,
            event.playerUuid, event.sourceServerId, event.targetServerId, reason, Instant.now()))
            .whenComplete { _, failure -> if (failure != null) logger.severe("Could not publish transfer failure ${event.transferId}: ${failure.message}") }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun gateJoin(event: PlayerJoinEvent) { loading += event.player.uniqueId }

    fun loadOnJoin(player: Player) {
        loading += player.uniqueId
        val prepare = incoming[player.uniqueId]
        val expected = prepare?.let {
            ready[player.uniqueId]?.takeIf { ready -> ready.transferId == it.transferId && ready.sourceServerId == it.sourceServerId && ready.targetServerId == it.targetServerId }
        }
        if (prepare != null && expected == null) return
        loadState(player, expected)
    }

    private fun loadState(player: Player, transferReady: PlayerTransferReady?) {
        service.load(player.uniqueId, serverId).whenComplete { loaded, failure -> onMain {
            if (transferReady != null && (incoming[player.uniqueId]?.transferId != transferReady.transferId || ready[player.uniqueId]?.transferId != transferReady.transferId)) return@onMain
            loading -= player.uniqueId
            if (!accepting || !player.isOnline) return@onMain
            if (failure != null) {
                val cause = failure.cause ?: failure
                logger.severe("Could not load TitanSMP player state for ${player.uniqueId}: ${cause.message}")
                transferReady?.let { readyEvent -> incoming[player.uniqueId]?.takeIf { it.transferId == readyEvent.transferId }?.let { publishTransferFailure(it, "target-state-load-failed") } }
                player.kick(net.kyori.adventure.text.Component.text("Your player data could not be loaded. Please reconnect in a moment."))
                return@onMain
            }
            try {
                if (transferReady != null && (loaded?.revision ?: 0L) < transferReady.stateRevision) {
                    logger.severe("Target loaded revision ${loaded?.revision ?: 0} below required ${transferReady.stateRevision} for transfer ${transferReady.transferId}")
                    val prepare = incoming[player.uniqueId]
                    if (prepare != null) publishTransferFailure(prepare, "target-state-below-required-revision")
                    player.kick(net.kyori.adventure.text.Component.text("Your latest player data is not available. Please reconnect."))
                    return@onMain
                }
                if (loaded != null) apply(player, loaded) else resetToVanillaDefaults(player)
                val baseline = loaded ?: snapshot(player, revision = 0)
                states[player.uniqueId] = baseline
                transferReady?.let { readyEvent ->
                    eventBus.publish(PlayerTransferCompleted(EventMetadata(eventType = PlayerTransferCompleted.TYPE, sourceServerId = serverId),
                        readyEvent.transferId, player.uniqueId, readyEvent.sourceServerId, serverId, baseline.revision, Instant.now()))
                        .whenComplete { _, publishFailure -> if (publishFailure != null) logger.severe("Could not publish completed transfer ${readyEvent.transferId}: ${publishFailure.message}") }
                    incoming[player.uniqueId]?.takeIf { it.transferId == readyEvent.transferId }?.let { incoming.remove(player.uniqueId, it) }
                    ready.remove(player.uniqueId, readyEvent)
                }
            } catch (problem: Throwable) {
                logger.severe("Could not apply TitanSMP player state for ${player.uniqueId}: ${problem.message}")
                transferReady?.let { readyEvent -> incoming[player.uniqueId]?.takeIf { it.transferId == readyEvent.transferId }?.let { publishTransferFailure(it, "target-state-apply-failed") } }
                player.kick(net.kyori.adventure.text.Component.text("Your player data could not be applied safely. Please reconnect."))
            }
        } }
    }

    fun rejectJoin(playerUuid: UUID, reason: String) = onMain {
        loading -= playerUuid
        Bukkit.getPlayer(playerUuid)?.kick(net.kyori.adventure.text.Component.text(reason))
    }

    /** Snapshot/save hook called before identity marks the player offline. */
    fun saveOnQuit(player: Player): CompletionStage<Unit> {
        loading -= player.uniqueId
        incoming.remove(player.uniqueId)
        ready.remove(player.uniqueId)
        sourceTransfers.remove(player.uniqueId)
        val baseline = states.remove(player.uniqueId) ?: return CompletableFuture.completedFuture(Unit)
        return saveIfDirty(player, baseline)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun freezeMovement(event: PlayerMoveEvent) { if (event.player.uniqueId in loading && event.hasChangedPosition()) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun freezeInventory(event: InventoryClickEvent) { if (event.whoClicked.uniqueId in loading) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun freezeDrag(event: InventoryDragEvent) { if (event.whoClicked.uniqueId in loading) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun freezeInteract(event: PlayerInteractEvent) { if (event.player.uniqueId in loading) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun freezeDrop(event: PlayerDropItemEvent) { if (event.player.uniqueId in loading) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun freezePickup(event: PlayerAttemptPickupItemEvent) { if (event.player.uniqueId in loading) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun freezeHandSwap(event: PlayerSwapHandItemsEvent) { if (event.player.uniqueId in loading) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun freezeDamage(event: EntityDamageEvent) { (event.entity as? Player)?.let { if (it.uniqueId in loading) event.isCancelled = true } }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun freezeHealthRegeneration(event: EntityRegainHealthEvent) { (event.entity as? Player)?.let { if (it.uniqueId in loading) event.isCancelled = true } }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun freezeFoodChange(event: FoodLevelChangeEvent) { (event.entity as? Player)?.let { if (it.uniqueId in loading) event.isCancelled = true } }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun freezeExperienceChange(event: PlayerExpChangeEvent) { if (event.player.uniqueId in loading) event.amount = 0 }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun freezeItemConsume(event: PlayerItemConsumeEvent) { if (event.player.uniqueId in loading) event.isCancelled = true }

    /** Snapshot all online players synchronously on disable, then persist and drain the DB worker. */
    fun flushAndClose(): CompletionStage<Unit> {
        accepting = false
        subscriptions.forEach(EventSubscription::close)
        subscriptions.clear()
        Bukkit.getOnlinePlayers().forEach { player -> states.remove(player.uniqueId)?.let { saveIfDirty(player, it) } }
        val pending = CompletableFuture.allOf(*saves.toTypedArray())
        return pending.handle { _, failure ->
            io.shutdown()
            if (failure != null) logger.severe("One or more TitanSMP player-state shutdown saves failed: ${failure.cause?.message ?: failure.message}")
            CompletableFuture.completedFuture(Unit)
        }.thenCompose { it }
    }

    private fun saveIfDirty(player: Player, baseline: PlayerState): CompletionStage<Unit> {
        val next = try { snapshot(player, baseline.revision) } catch (failure: Throwable) {
            logger.severe("Could not capture TitanSMP player state for ${player.uniqueId}: ${failure.message}")
            return CompletableFuture.failedFuture(failure)
        }
        if (sameState(baseline, next) && baseline.revision > 0) return CompletableFuture.completedFuture(Unit)
        val saving = service.save(next, serverId).thenApply { saved ->
            eventBus.publish(PlayerStateUpdated(EventMetadata(eventType = PlayerStateUpdated.TYPE, sourceServerId = serverId), player.uniqueId, saved.revision))
                .whenComplete { _, failure -> if (failure != null) logger.severe("Could not publish player-state update for ${player.uniqueId}: ${failure.message}") }
            Unit
        }
        track(saving)
        return saving
    }

    private fun track(stage: CompletionStage<*>) {
        val future = stage.toCompletableFuture()
        saves += future
        future.whenComplete { _, failure ->
            saves -= future
            if (failure != null) logger.severe("TitanSMP player-state save failed: ${failure.cause?.message ?: failure.message}")
        }
    }

    private fun snapshot(player: Player, revision: Long): PlayerState {
        val inventory = player.inventory
        val armor = inventory.armorContents // Bukkit order: boots, leggings, chestplate, helmet
        return PlayerState(
            player.uniqueId,
            inventory.storageContents.map(::encode),
            listOf(encode(armor.getOrNull(3)), encode(armor.getOrNull(2)), encode(armor.getOrNull(1)), encode(armor.getOrNull(0))),
            encode(inventory.itemInOffHand),
            player.health.coerceIn(0.0, player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: player.health),
            player.foodLevel.coerceIn(0, 20),
            player.exp.coerceIn(0.0f, 1.0f),
            player.level.coerceAtLeast(0),
            player.totalExperience.coerceAtLeast(0),
            revision,
        )
    }

    private fun apply(player: Player, state: PlayerState) {
        player.inventory.storageContents = state.inventory.map(::decode).toTypedArray()
        player.inventory.armorContents = arrayOf(decode(state.armor[3]), decode(state.armor[2]), decode(state.armor[1]), decode(state.armor[0]))
        player.inventory.setItemInOffHand(decode(state.offhand))
        player.foodLevel = state.foodLevel
        player.totalExperience = state.totalExperience
        player.level = state.level
        player.exp = state.experience
        val maxHealth = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: state.health
        player.health = state.health.coerceIn(0.0, maxHealth)
    }

    private fun resetToVanillaDefaults(player: Player) {
        player.inventory.storageContents = arrayOfNulls(PlayerState.INVENTORY_SLOTS)
        player.inventory.armorContents = arrayOfNulls(PlayerState.ARMOR_SLOTS)
        player.inventory.setItemInOffHand(null)
        player.foodLevel = 20
        player.totalExperience = 0
        player.level = 0
        player.exp = 0f
        val defaultHealth = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: 20.0
        player.health = defaultHealth
    }

    private fun encode(item: ItemStack?): SerializedPlayerItem? =
        PaperItemStackCodec.encode(item)?.let(::SerializedPlayerItem)

    private fun decode(item: SerializedPlayerItem?): ItemStack? = PaperItemStackCodec.decode(item?.payload)

    private fun sameState(left: PlayerState, right: PlayerState): Boolean = left.copy(revision = 0) == right.copy(revision = 0)

    private fun onMain(action: () -> Unit) {
        if (Bukkit.isPrimaryThread()) action()
        else if (plugin.isEnabled) Bukkit.getScheduler().runTask(plugin, Runnable(action))
    }
}
