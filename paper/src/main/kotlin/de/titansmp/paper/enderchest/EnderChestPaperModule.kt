package de.titansmp.paper.enderchest

import de.titansmp.api.enderchest.EnderChestPermissionCheck
import de.titansmp.api.enderchest.EnderChestService
import de.titansmp.api.enderchest.EnderChestStorage
import de.titansmp.api.enderchest.EnderChestStorageConflictException
import de.titansmp.api.enderchest.SerializedEnderChestItem
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

/** Paper adapter: live inventory access stays on the server thread; JDBC stays on its worker. */
class EnderChestPaperModule(
    private val plugin: JavaPlugin,
    private val service: EnderChestService,
    private val serverId: String,
    private val permissionsFor: (Player) -> EnderChestPermissionCheck,
    private val logger: Logger = plugin.logger,
    private val closePersistence: () -> Unit = {},
) : Listener, org.bukkit.command.CommandExecutor {
    private val sessions = ConcurrentHashMap<UUID, EnderChestSession>()
    private val pendingOpens = ConcurrentHashMap.newKeySet<UUID>()
    private val pendingSaves = ConcurrentHashMap.newKeySet<CompletableFuture<*>>()
    @Volatile private var accepting = true

    fun register() {
        Bukkit.getPluginManager().registerEvents(this, plugin)
        requireNotNull(plugin.getCommand("ec")) { "The /ec command must be declared in plugin.yml" }.setExecutor(this)
    }

    override fun onCommand(
        sender: org.bukkit.command.CommandSender,
        command: org.bukkit.command.Command,
        label: String,
        args: Array<out String>,
    ): Boolean {
        if (sender !is Player) {
            sender.sendMessage("Only players can open the TitanSMP EnderChest.")
            return true
        }
        open(sender)
        return true
    }

    private fun open(player: Player) {
        if (!accepting || !player.isOnline) return
        val existing = sessions[player.uniqueId]
        if (existing != null) {
            if (refreshRows(player, existing)) player.openInventory(existing.inventory)
            return
        }
        if (!pendingOpens.add(player.uniqueId)) return

        service.load(player.uniqueId, serverId).whenComplete { storage, failure ->
            runOnServerThread {
                pendingOpens.remove(player.uniqueId)
                if (!accepting || !player.isOnline) return@runOnServerThread
                if (failure != null) {
                    logger.severe("Could not load TitanSMP EnderChest for ${player.uniqueId}: ${failure.message}")
                    player.sendMessage(Component.text("Your EnderChest could not be loaded. Please try again.", NamedTextColor.RED))
                    return@runOnServerThread
                }
                val rows = unlockedRows(player)
                val session = EnderChestSession(storage, rows)
                session.render()
                sessions[player.uniqueId] = session
                player.openInventory(session.inventory)
            }
        }
    }

    private fun unlockedRows(player: Player): Int = service.unlockedRows(permissionsFor(player))

    /** Returns true if the action should be cancelled because the permission view changed this tick. */
    private fun refreshRows(player: Player, session: EnderChestSession): Boolean {
        val newRows = unlockedRows(player)
        if (newRows == session.rows) return false
        session.changeRows(newRows)
        return true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEnderChestInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.clickedBlock?.type != Material.ENDER_CHEST) return
        event.isCancelled = true
        open(event.player)
    }

    /** Also blocks vanilla inventories opened by other plugins or direct openInventory calls. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onVanillaEnderChestOpen(event: InventoryOpenEvent) {
        if (event.inventory.type != org.bukkit.event.inventory.InventoryType.ENDER_CHEST) return
        val player = event.player as? Player ?: return
        event.isCancelled = true
        runOnServerThread { open(player) }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder
        if (holder !is TitanEnderChestHolder) return
        val player = event.whoClicked as? Player ?: run { event.isCancelled = true; return }
        val session = sessions[player.uniqueId] ?: run { event.isCancelled = true; return }

        if (refreshRows(player, session)) {
            event.isCancelled = true
            return
        }
        val top = event.view.topInventory
        val lockedTopSlot = event.rawSlot in 0 until top.size && event.rawSlot >= session.unlockedSlots
        val dangerousAction = event.isShiftClick || event.click == ClickType.DOUBLE_CLICK || event.action in BLOCKED_ACTIONS
        val bottomShiftClick = event.clickedInventory === event.view.bottomInventory && event.isShiftClick
        if (lockedTopSlot || dangerousAction || bottomShiftClick) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder !is TitanEnderChestHolder) return
        val player = event.whoClicked as? Player ?: run { event.isCancelled = true; return }
        val session = sessions[player.uniqueId] ?: run { event.isCancelled = true; return }
        if (refreshRows(player, session) || event.rawSlots.any { it in 0 until event.view.topInventory.size && it >= session.unlockedSlots }) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDrop(event: PlayerDropItemEvent) {
        if (sessions.containsKey(event.player.uniqueId)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onOffhandSwap(event: PlayerSwapHandItemsEvent) {
        if (sessions.containsKey(event.player.uniqueId)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onInventoryClose(event: InventoryCloseEvent) {
        if (event.inventory.holder !is TitanEnderChestHolder) return
        val player = event.player as? Player ?: return
        val session = sessions.remove(player.uniqueId) ?: return
        refreshRows(player, session)
        persist(session, player, notifyPlayer = true)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val session = sessions.remove(event.player.uniqueId) ?: return
        refreshRows(event.player, session)
        persist(session, event.player, notifyPlayer = false)
    }

    /** Called on Paper's main thread from plugin shutdown; snapshots first, flushes asynchronously. */
    fun flushAndClose(): CompletionStage<Unit> {
        accepting = false
        sessions.entries.toList().forEach { (uuid, session) ->
            val player = Bukkit.getPlayer(uuid)
            if (player != null) refreshRows(player, session)
            sessions.remove(uuid, session)
            persist(session, player, notifyPlayer = false)
        }
        val saves = pendingSaves.toTypedArray()
        return CompletableFuture.allOf(*saves).thenApply { Unit }.whenComplete { _, _ -> closePersistence() }
    }

    private fun persist(session: EnderChestSession, player: Player?, notifyPlayer: Boolean) {
        if (!session.persisted.compareAndSet(false, true)) return
        val updated = try {
            session.snapshot()
        } catch (failure: Exception) {
            logger.severe("Could not serialize TitanSMP EnderChest for ${session.storage.playerId}: ${failure.message}")
            return
        }
        if (updated.slots == session.storage.slots) return

        val future = service.save(updated, serverId).toCompletableFuture()
        pendingSaves += future
        future.whenComplete { _, failure ->
            pendingSaves -= future
            if (failure == null) return@whenComplete
            val cause = failure.cause ?: failure
            val conflict = cause is EnderChestStorageConflictException
            logger.severe(
                if (conflict) "Rejected stale EnderChest save for ${updated.playerId}; newer storage was preserved."
                else "Could not save TitanSMP EnderChest for ${updated.playerId}: ${cause.message}",
            )
            if (notifyPlayer && player != null) runOnServerThread {
                if (player.isOnline) player.sendMessage(
                    Component.text(
                        if (conflict) "Your EnderChest changed on another server. The newer storage was kept; reopen it to load that version."
                        else "Your EnderChest could not be saved. Please contact staff if this continues.",
                        NamedTextColor.RED,
                    ),
                )
            }
        }
    }

    private fun runOnServerThread(action: () -> Unit) {
        if (!plugin.isEnabled) return
        Bukkit.getScheduler().runTask(plugin, Runnable(action))
    }

    private inner class EnderChestSession(
        initialStorage: EnderChestStorage,
        rows: Int,
    ) {
        var storage: EnderChestStorage = initialStorage
            private set
        var rows: Int = rows
            private set
        val unlockedSlots: Int get() = rows * 27
        val persisted = AtomicBoolean(false)
        private val holder = TitanEnderChestHolder()
        val inventory: Inventory = Bukkit.createInventory(holder, EnderChestStorage.CAPACITY, Component.text("TitanSMP EnderChest"))
            .also(holder::attach)
        private val backingSlots: MutableList<SerializedEnderChestItem?> = initialStorage.slots.toMutableList()

        fun render() {
            for (slot in 0 until EnderChestStorage.CAPACITY) {
                inventory.setItem(slot, if (slot < unlockedSlots) decode(backingSlots[slot]) else lockedSlot())
            }
        }

        fun changeRows(newRows: Int) {
            require(newRows in 1..2)
            captureVisibleSlots()
            rows = newRows
            render()
        }

        fun snapshot(): EnderChestStorage {
            captureVisibleSlots()
            return EnderChestStorage(storage.playerId, backingSlots.toList(), storage.revision)
        }

        private fun captureVisibleSlots() {
            for (slot in 0 until unlockedSlots) backingSlots[slot] = encode(inventory.getItem(slot))
        }

        private fun encode(item: ItemStack?): SerializedEnderChestItem? =
            if (item == null || item.type.isAir) null else SerializedEnderChestItem(item.serializeAsBytes())

        private fun decode(item: SerializedEnderChestItem?): ItemStack? = item?.let { ItemStack.deserializeBytes(it.payload) }

        private fun lockedSlot(): ItemStack = ItemStack(Material.BARRIER).apply {
            itemMeta = itemMeta.apply {
                displayName(Component.text("Locked slot", NamedTextColor.RED))
                lore(listOf(Component.text("Requires titansmp.enderchest.rows.${rows + 1}", NamedTextColor.GRAY)))
            }
        }
    }

    private inner class TitanEnderChestHolder : InventoryHolder {
        override fun getInventory(): Inventory = inventoryRef
        private lateinit var inventoryRef: Inventory
        init {
            // Bukkit asks the holder for its inventory after createInventory returns.
        }
        fun attach(inventory: Inventory) { inventoryRef = inventory }
    }

    private companion object {
        val BLOCKED_ACTIONS = setOf(
            InventoryAction.COLLECT_TO_CURSOR,
            InventoryAction.DROP_ALL_SLOT,
            InventoryAction.DROP_ONE_SLOT,
            InventoryAction.HOTBAR_MOVE_AND_READD,
            InventoryAction.HOTBAR_SWAP,
            InventoryAction.SWAP_WITH_CURSOR,
            InventoryAction.UNKNOWN,
        )
    }
}
