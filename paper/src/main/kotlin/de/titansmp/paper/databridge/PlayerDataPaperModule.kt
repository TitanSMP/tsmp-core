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
import de.titansmp.api.databridge.PotionEffectData
import de.titansmp.api.databridge.AdvancementProgressData
import de.titansmp.api.databridge.CriterionProgressData
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
import org.bukkit.event.entity.EntityPotionEffectEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerAdvancementDoneEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerAttemptPickupItemEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.potion.PotionEffect
import com.destroystokyo.paper.event.player.PlayerAdvancementCriterionGrantEvent
import org.bukkit.advancement.Advancement
import org.bukkit.advancement.AdvancementProgress
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
    private val advancementAccess: PaperAdvancementAccess = BukkitPaperAdvancementAccess,
) : Listener {
    private val loading = ConcurrentHashMap.newKeySet<UUID>()
    private val states = ConcurrentHashMap<UUID, PlayerState>()
    private val saves = ConcurrentHashMap.newKeySet<CompletableFuture<*>>()
    private val dirtyPotionEffects = ConcurrentHashMap.newKeySet<UUID>()
    private val dirtyAdvancements = ConcurrentHashMap.newKeySet<UUID>()
    private val deferredAdvancements = ConcurrentHashMap<UUID, MutableSet<String>>()
    private val restoringAdvancements = ConcurrentHashMap.newKeySet<UUID>()
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
        val next = try { snapshot(player, baseline.revision, baseline) } catch (failure: Throwable) {
            loading -= event.playerUuid
            sourceTransfers.remove(event.playerUuid, event.transferId)
            logger.severe("Could not capture state for transfer ${event.transferId}: ${failure.message}")
            publishTransferFailure(event, "state-capture-failed")
            return@onMain
        }
        val save = if (sameState(baseline, next) && baseline.revision > 0 && event.playerUuid !in dirtyPotionEffects && event.playerUuid !in dirtyAdvancements) CompletableFuture.completedFuture(baseline)
            else service.save(next, serverId).toCompletableFuture()
        track(save)
        save.whenComplete { saved, failure -> onMain {
            if (failure != null) {
                loading -= event.playerUuid
                sourceTransfers.remove(event.playerUuid, event.transferId)
                logger.severe("Could not save state for transfer ${event.transferId}: ${failure.cause?.message ?: failure.message}")
                Bukkit.getPlayer(event.playerUuid)?.sendMessage(net.kyori.adventure.text.Component.text("Your data could not be saved, so the transfer was cancelled."))
                publishTransferFailure(event, "state-save-failed")
            } else if (sourceTransfers[event.playerUuid] == event.transferId) {
                persistLatestAdvancementsBeforeReady(event, player, saved, 0)
            }
        } }
    }

    /** Re-snapshot after each async save so grants made while the transfer save was in flight are included. */
    private fun persistLatestAdvancementsBeforeReady(event: PlayerTransferPrepare, player: Player, saved: PlayerState, attempts: Int) {
        if (sourceTransfers[event.playerUuid] != event.transferId) return
        val latest = try { snapshot(player, saved.revision, saved) } catch (failure: Throwable) {
            failSourceTransfer(event, "state-capture-failed", "Could not capture final state for transfer ${event.transferId}: ${failure.message}")
            return
        }
        if (latest.advancements != saved.advancements) {
            if (attempts >= 3) {
                failSourceTransfer(event, "advancement-state-kept-changing", "Advancement progress kept changing during transfer ${event.transferId}")
                return
            }
            val resave = service.save(latest, serverId).toCompletableFuture()
            track(resave)
            resave.whenComplete { updated, failure -> onMain {
                if (failure != null) failSourceTransfer(event, "state-save-failed", "Could not save final advancement state for transfer ${event.transferId}: ${failure.cause?.message ?: failure.message}")
                else persistLatestAdvancementsBeforeReady(event, player, updated, attempts + 1)
            } }
            return
        }
        states[event.playerUuid] = saved
        dirtyPotionEffects -= event.playerUuid
        dirtyAdvancements -= event.playerUuid
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

    private fun failSourceTransfer(event: PlayerTransferPrepare, reason: String, message: String) {
        loading -= event.playerUuid
        sourceTransfers.remove(event.playerUuid, event.transferId)
        logger.severe(message)
        Bukkit.getPlayer(event.playerUuid)?.sendMessage(net.kyori.adventure.text.Component.text("Your data could not be saved, so the transfer was cancelled."))
        publishTransferFailure(event, reason)
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
                deferredAdvancements.remove(player.uniqueId)
                restoringAdvancements += player.uniqueId
                try {
                    if (loaded != null) apply(player, loaded) else resetToVanillaDefaults(player)
                } finally {
                    restoringAdvancements -= player.uniqueId
                }
                val baseline = snapshot(player, revision = loaded?.revision ?: 0, previous = loaded)
                states[player.uniqueId] = baseline
                dirtyPotionEffects -= player.uniqueId
                dirtyAdvancements -= player.uniqueId
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
        val baseline = states.remove(player.uniqueId) ?: run {
            dirtyPotionEffects.remove(player.uniqueId)
            dirtyAdvancements.remove(player.uniqueId)
            deferredAdvancements.remove(player.uniqueId)
            return CompletableFuture.completedFuture(Unit)
        }
        val saving = saveIfDirty(player, baseline)
        dirtyPotionEffects.remove(player.uniqueId)
        dirtyAdvancements.remove(player.uniqueId)
        deferredAdvancements.remove(player.uniqueId)
        return saving
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun trackPotionEffectChange(event: EntityPotionEffectEvent) {
        val player = event.entity as? Player ?: return
        if (player.uniqueId !in loading) dirtyPotionEffects += player.uniqueId
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun trackPotionEffectsAfterDeath(event: PlayerDeathEvent) {
        dirtyPotionEffects += event.entity.uniqueId
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun trackAdvancementCriterionGrant(event: PlayerAdvancementCriterionGrantEvent) {
        val playerId = event.player.uniqueId
        if (playerId in restoringAdvancements || (playerId in loading && !sourceTransfers.containsKey(playerId))) return
        dirtyAdvancements += playerId
        deferredAdvancements[playerId]?.remove(event.advancement.key.toString())
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun trackAdvancementCompletion(event: PlayerAdvancementDoneEvent) {
        val playerId = event.player.uniqueId
        if (playerId in restoringAdvancements || (playerId in loading && !sourceTransfers.containsKey(playerId))) return
        dirtyAdvancements += playerId
        deferredAdvancements[playerId]?.remove(event.advancement.key.toString())
    }

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
        val next = try { snapshot(player, baseline.revision, baseline) } catch (failure: Throwable) {
            logger.severe("Could not capture TitanSMP player state for ${player.uniqueId}: ${failure.message}")
            return CompletableFuture.failedFuture(failure)
        }
        if (sameState(baseline, next) && baseline.revision > 0 && player.uniqueId !in dirtyPotionEffects && player.uniqueId !in dirtyAdvancements) return CompletableFuture.completedFuture(Unit)
        val saving = service.save(next, serverId).thenApply { saved ->
            dirtyPotionEffects -= player.uniqueId
            dirtyAdvancements -= player.uniqueId
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

    private fun snapshot(player: Player, revision: Long, previous: PlayerState? = null): PlayerState {
        val inventory = player.inventory
        val armor = inventory.armorContents // Bukkit order: boots, leggings, chestplate, helmet
        val advancements = advancementAccess.all().asSequence().map { advancement ->
            val progress = advancementAccess.progress(player, advancement)
            val awarded = progress.awardedCriteria.toSet()
            AdvancementProgressData(
                advancement.key.toString(),
                advancement.criteria.sorted().map { criterion -> CriterionProgressData(criterion, criterion in awarded) },
            )
        }.toList().associateBy { it.advancementKey }.toMutableMap()
        val deferred = deferredAdvancements[player.uniqueId].orEmpty()
        previous?.advancements.orEmpty().filter { saved ->
            saved.advancementKey in deferred || NamespacedKey.fromString(saved.advancementKey)?.let(advancementAccess::find) == null
        }.forEach { advancements[it.advancementKey] = it }
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
            player.activePotionEffects.map(::encodeEffect).sortedBy { it.type },
            Instant.ofEpochMilli(System.currentTimeMillis()),
            advancements.values.sortedBy { it.advancementKey },
        )
    }

    private fun apply(player: Player, state: PlayerState) {
        applyAdvancements(player, state.advancements)
        // A successfully loaded state is authoritative. Clear target-local/plugin effects only now.
        player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
        val now = Instant.now()
        state.potionEffects.forEach { data ->
            val key = NamespacedKey.fromString(data.type) ?: run {
                logger.warning("Ignoring invalid potion effect identifier '${data.type}' for ${player.uniqueId}")
                return@forEach
            }
            val type = Registry.EFFECT.get(key) ?: run {
                logger.warning("Potion effect '${data.type}' is unavailable on this Paper version for ${player.uniqueId}")
                return@forEach
            }
            player.addPotionEffect(data.toPaperEffect(state.capturedAt, now) ?: return@forEach)
        }
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
        applyAdvancements(player, emptyList())
        player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
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

    private fun applyAdvancements(player: Player, saved: List<AdvancementProgressData>) {
        val savedByKey = saved.associateBy { it.advancementKey }
        val deferred = deferredAdvancements.computeIfAbsent(player.uniqueId) { ConcurrentHashMap.newKeySet() }
        val localKeys = mutableSetOf<String>()
        advancementAccess.all().forEachRemaining { advancement ->
            val key = advancement.key.toString()
            localKeys += key
            val progress = advancementAccess.progress(player, advancement)
            val data = savedByKey[key]
            if (data != null) applyAdvancement(player, advancement, progress, data, deferred)
        }
        saved.filter { it.advancementKey !in localKeys }.forEach { missing ->
            deferred += missing.advancementKey
            logger.warning("Keeping advancement '${missing.advancementKey}' in the saved player state because it is not registered on this server")
        }
    }

    private fun applyAdvancement(
        player: Player,
        advancement: Advancement,
        progress: AdvancementProgress,
        saved: AdvancementProgressData,
        deferred: MutableSet<String>,
    ) {
        val targetCriteria = advancement.criteria.toSet()
        val savedByCriterion = saved.criteria.associate { it.criterionKey to it.completed }
        if (savedByCriterion.keys.any { it !in targetCriteria }) {
            deferred += saved.advancementKey
            logger.warning("Keeping unmatched criteria for advancement '${saved.advancementKey}' in the saved player state")
        }
        val desired = targetCriteria.filterTo(mutableSetOf()) { savedByCriterion[it] == true }
        val desiredCompletes = advancement.requirements.requirements.all { requirement ->
            requirement.requiredCriteria.any { it in desired }
        }
        val current = progress.awardedCriteria.toSet()
        if ((desiredCompletes && !progress.isDone) || (desiredCompletes && current != desired)) {
            // Awarding the final criterion can execute arbitrary definition rewards; Paper exposes no reward-free grant.
            deferred += saved.advancementKey
            logger.warning("Deferring completion restore for advancement '${saved.advancementKey}' to avoid replaying rewards")
            return
        }
        (current - desired).forEach(progress::revokeCriteria)
        (desired - current).forEach(progress::awardCriteria)
        if (savedByCriterion.keys.all { it in targetCriteria }) deferred -= saved.advancementKey
    }

    private fun encodeEffect(effect: PotionEffect): PotionEffectData = PotionEffectData(
        effect.type.key.toString(),
        if (effect.isInfinite) 0 else effect.duration,
        effect.amplifier,
        effect.isAmbient,
        effect.hasParticles(),
        effect.hasIcon(),
        effect.isInfinite,
        effect.hiddenPotionEffect?.let(::encodeEffect),
    )

    private fun PotionEffectData.toPaperEffect(capturedAt: Instant, now: Instant): PotionEffect? {
        val remaining = remainingTicksAt(capturedAt, now) ?: return null
        val key = NamespacedKey.fromString(type) ?: return null
        val effectType = Registry.EFFECT.get(key) ?: return null
        val hidden = hiddenEffect?.toPaperEffect(capturedAt, now)
        return PotionEffect(effectType, remaining,
            amplifier, ambient, particles, icon, hidden)
    }


    private fun sameState(left: PlayerState, right: PlayerState): Boolean =
        left.copy(revision = 0, capturedAt = Instant.EPOCH) == right.copy(revision = 0, capturedAt = Instant.EPOCH)

    private fun onMain(action: () -> Unit) {
        if (Bukkit.isPrimaryThread()) action()
        else if (plugin.isEnabled) Bukkit.getScheduler().runTask(plugin, Runnable(action))
    }
}
