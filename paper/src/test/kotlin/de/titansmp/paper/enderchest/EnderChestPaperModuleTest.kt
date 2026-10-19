package de.titansmp.paper.enderchest

import de.titansmp.api.enderchest.EnderChestPermissionCheck
import de.titansmp.api.enderchest.EnderChestService
import de.titansmp.api.enderchest.EnderChestStorage
import de.titansmp.api.enderchest.SerializedEnderChestItem
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.BlockFace
import org.bukkit.command.Command
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import net.kyori.adventure.text.Component
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import java.util.UUID
import java.util.concurrent.CompletableFuture

class EnderChestPaperModuleTest {
    private lateinit var server: ServerMock
    private lateinit var plugin: TestPaperPlugin
    private lateinit var player: org.mockbukkit.mockbukkit.entity.PlayerMock
    private lateinit var storage: FakeEnderChestService
    private lateinit var module: EnderChestPaperModule
    private var rows = 1

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        plugin = MockBukkit.load(TestPaperPlugin::class.java)
        player = server.addPlayer()
        val item = ItemStack(Material.DIAMOND_SWORD).serializeAsBytes()
        val slots = MutableList<SerializedEnderChestItem?>(54) { null }
        slots[40] = SerializedEnderChestItem(item)
        storage = FakeEnderChestService(EnderChestStorage(player.uniqueId, slots, 3))
        module = EnderChestPaperModule(plugin, storage, "smp-01", { EnderChestPermissionCheck { permission ->
            permission == "titansmp.enderchest.rows.$rows"
        } })
        Bukkit.getPluginManager().registerEvents(module, plugin)
    }

    @AfterEach
    fun tearDown() {
        if (::module.isInitialized) module.flushAndClose().toCompletableFuture().join()
        MockBukkit.unmock()
    }

    @Test
    fun `ec opens a fixed 54 slot inventory and preserves inaccessible item`() {
        commandOpen()
        val top = player.openInventory.topInventory

        assertEquals(54, top.size)
        assertEquals(Material.BARRIER, top.getItem(40)?.type)
        assertEquals(Component.text("TitanSMP EnderChest"), player.openInventory.title())
        assertEquals(1, storage.loads)
    }

    @Test
    fun `vanilla EnderChest interaction opens the same stored contents`() {
        commandOpen()
        assertEquals(Material.BARRIER, player.openInventory.topInventory.getItem(40)?.type)
        player.closeInventory()

        val world = server.addSimpleWorld("ender-chest-test")
        val block = world.getBlockAt(0, 64, 0)
        block.type = Material.ENDER_CHEST
        val interaction = PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null, block, BlockFace.NORTH)
        Bukkit.getPluginManager().callEvent(interaction)
        assertTrue(interaction.isCancelled)
        server.scheduler.performTicks(1)

        assertEquals(54, player.openInventory.topInventory.size)
        assertEquals(Material.BARRIER, player.openInventory.topInventory.getItem(40)?.type)
        assertEquals(2, storage.loads)
    }

    @Test
    fun `locked slot click is blocked and permission change restores preserved item`() {
        commandOpen()
        val view = player.openInventory
        val click = InventoryClickEvent(
            view,
            InventoryType.SlotType.CONTAINER,
            40,
            ClickType.NUMBER_KEY,
            InventoryAction.HOTBAR_SWAP,
            0,
        )
        Bukkit.getPluginManager().callEvent(click)
        assertTrue(click.isCancelled)

        rows = 2
        val refresh = InventoryClickEvent(
            view,
            InventoryType.SlotType.CONTAINER,
            1,
            ClickType.LEFT,
            InventoryAction.PICKUP_ALL,
        )
        Bukkit.getPluginManager().callEvent(refresh)
        assertTrue(refresh.isCancelled)
        assertEquals(Material.DIAMOND_SWORD, player.openInventory.topInventory.getItem(40)?.type)
    }

    @Test
    fun `locked slot drag and item-moving click actions are cancelled`() {
        commandOpen()
        val view = player.openInventory
        val drag = InventoryDragEvent(
            view,
            ItemStack(Material.DIAMOND),
            ItemStack(Material.AIR),
            true,
            mapOf(40 to ItemStack(Material.DIAMOND)),
        )
        Bukkit.getPluginManager().callEvent(drag)
        assertTrue(drag.isCancelled)

        listOf(
            InventoryAction.DROP_ALL_SLOT,
            InventoryAction.DROP_ONE_SLOT,
            InventoryAction.HOTBAR_SWAP,
            InventoryAction.SWAP_WITH_CURSOR,
            InventoryAction.COLLECT_TO_CURSOR,
        ).forEach { action ->
            val click = InventoryClickEvent(
                view,
                InventoryType.SlotType.CONTAINER,
                40,
                ClickType.LEFT,
                action,
            )
            Bukkit.getPluginManager().callEvent(click)
            assertTrue(click.isCancelled, "Expected $action on a locked slot to be cancelled")
        }
        val swap = PlayerSwapHandItemsEvent(player, ItemStack(Material.AIR), ItemStack(Material.AIR))
        Bukkit.getPluginManager().callEvent(swap)
        assertTrue(swap.isCancelled)
    }

    @Test
    fun `quit fallback persists active storage when close event did not run`() {
        commandOpen()
        player.openInventory.topInventory.setItem(2, ItemStack(Material.EMERALD))

        Bukkit.getPluginManager().callEvent(PlayerQuitEvent(player, "quit"))

        assertEquals(1, storage.saves)
        assertEquals(Material.EMERALD, ItemStack.deserializeBytes(storage.storage.slots[2]!!.payload).type)
    }

    @Test
    fun `downgrade hides but does not remove an item from a newly locked slot`() {
        rows = 2
        commandOpen()
        player.openInventory.topInventory.setItem(40, ItemStack(Material.NETHERITE_PICKAXE))

        rows = 1
        val refresh = InventoryClickEvent(
            player.openInventory,
            InventoryType.SlotType.CONTAINER,
            1,
            ClickType.LEFT,
            InventoryAction.PICKUP_ALL,
        )
        Bukkit.getPluginManager().callEvent(refresh)
        assertTrue(refresh.isCancelled)
        assertEquals(Material.BARRIER, player.openInventory.topInventory.getItem(40)?.type)
        player.closeInventory()
        assertEquals(1, storage.saves)
        assertEquals(Material.NETHERITE_PICKAXE, ItemStack.deserializeBytes(storage.storage.slots[40]!!.payload).type)
    }

    @Test
    fun `Paper item bytes preserve name lore and item persistent data`() {
        val original = ItemStack(Material.DIAMOND_SWORD)
        val key = NamespacedKey(plugin, "test_data")
        original.itemMeta = original.itemMeta.apply {
            displayName(Component.text("Named sword"))
            lore(listOf(Component.text("Line one"), Component.text("Line two")))
            persistentDataContainer.set(key, PersistentDataType.STRING, "preserved")
        }

        val restored = ItemStack.deserializeBytes(original.serializeAsBytes())
        assertEquals(original.itemMeta.displayName(), restored.itemMeta.displayName())
        assertEquals(original.itemMeta.lore(), restored.itemMeta.lore())
        assertEquals("preserved", restored.itemMeta.persistentDataContainer.get(key, PersistentDataType.STRING))
    }

    private fun commandOpen() {
        module.onCommand(
            player,
            object : Command("ec") {
                override fun execute(sender: org.bukkit.command.CommandSender, label: String, args: Array<out String>): Boolean = true
            },
            "ec",
            emptyArray(),
        )
        server.scheduler.performTicks(1)
    }

    open class TestPaperPlugin : JavaPlugin()

    private class FakeEnderChestService(var storage: EnderChestStorage) : EnderChestService {
        var loads = 0
        var saves = 0

        override fun load(playerId: UUID, serverId: String): CompletableFuture<EnderChestStorage> {
            loads++
            return CompletableFuture.completedFuture(storage.copy(playerId = playerId))
        }

        override fun save(storage: EnderChestStorage, serverId: String): CompletableFuture<EnderChestStorage> {
            saves++
            this.storage = storage.copy(revision = storage.revision + 1)
            return CompletableFuture.completedFuture(this.storage)
        }

        override fun unlockedRows(permissionCheck: EnderChestPermissionCheck): Int =
            (2 downTo 1).firstOrNull { permissionCheck.hasPermission("titansmp.enderchest.rows.$it") } ?: 1
    }
}
