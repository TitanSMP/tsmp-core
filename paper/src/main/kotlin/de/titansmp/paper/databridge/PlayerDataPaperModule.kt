package de.titansmp.paper.databridge

import de.titansmp.api.databridge.PlayerDataService
import de.titansmp.api.databridge.PlayerState
import de.titansmp.api.databridge.PlayerStateUpdated
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
) : Listener {
    private val loading = ConcurrentHashMap.newKeySet<UUID>()
    private val states = ConcurrentHashMap<UUID, PlayerState>()
    private val saves = ConcurrentHashMap.newKeySet<CompletableFuture<*>>()
    @Volatile private var accepting = true

    fun register() {
        Bukkit.getPluginManager().registerEvents(this, plugin)
        Bukkit.getOnlinePlayers().forEach { loading += it.uniqueId }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun gateJoin(event: PlayerJoinEvent) { loading += event.player.uniqueId }

    fun loadOnJoin(player: Player) {
        loading += player.uniqueId
        service.load(player.uniqueId, serverId).whenComplete { loaded, failure -> onMain {
            loading -= player.uniqueId
            if (!accepting || !player.isOnline) return@onMain
            if (failure != null) {
                val cause = failure.cause ?: failure
                logger.severe("Could not load TitanSMP player state for ${player.uniqueId}: ${cause.message}")
                player.kick(net.kyori.adventure.text.Component.text("Your player data could not be loaded. Please reconnect in a moment."))
                return@onMain
            }
            try {
                if (loaded != null) apply(player, loaded) else resetToVanillaDefaults(player)
                val baseline = loaded ?: snapshot(player, revision = 0)
                states[player.uniqueId] = baseline
            } catch (problem: Throwable) {
                logger.severe("Could not apply TitanSMP player state for ${player.uniqueId}: ${problem.message}")
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

    /** Snapshot all online players synchronously on disable, then persist and drain the DB worker. */
    fun flushAndClose(): CompletionStage<Unit> {
        accepting = false
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
