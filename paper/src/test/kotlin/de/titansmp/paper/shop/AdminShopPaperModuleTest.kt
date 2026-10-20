package de.titansmp.paper.shop

import de.titansmp.api.economy.EconomyService
import de.titansmp.api.economy.Money
import de.titansmp.api.event.*
import de.titansmp.api.shop.ShopCategoryState
import de.titansmp.api.shop.ShopService
import de.titansmp.api.shop.ShopStateUpdate
import de.titansmp.core.config.ShopCategoryConfig
import de.titansmp.core.config.ShopConfig
import de.titansmp.core.config.ShopItemConfig
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.event.inventory.*
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture

class AdminShopPaperModuleTest {
    private lateinit var server:ServerMock
    private lateinit var plugin:ShopGuiTestPlugin
    private lateinit var player:org.mockbukkit.mockbukkit.entity.PlayerMock
    private lateinit var module:AdminShopPaperModule
    private lateinit var service:FakeShopService

    @BeforeEach fun setUp() {
        server=MockBukkit.mock();plugin=MockBukkit.load(ShopGuiTestPlugin::class.java);player=server.addPlayer("Shopper")
        player.addAttachment(plugin,"titansmp.shop.use",true)
        service=FakeShopService()
        val items=(0..45).map { index -> ShopItemConfig("item${index.toString().padStart(2,'0')}","APPLE",index%45,1,"Item $index",Money.of("1.00"),Money.of("0.50")) }
        val config=ShopConfig(categories=listOf(ShopCategoryConfig("food","Food","COOKED_BEEF",20,true,items)))
        module=AdminShopPaperModule(plugin,service,Proxy.newProxyInstance(EconomyService::class.java.classLoader,arrayOf(EconomyService::class.java)) { _,_,_->CompletableFuture.completedFuture(null) } as EconomyService,FakeEventBus(),config,"smp-test")
        Bukkit.getPluginManager().registerEvents(module,plugin)
    }

    @AfterEach fun tearDown(){module.close();MockBukkit.unmock()}

    @Test fun `disabled category renders barrier and a stale click cannot enter`() {
        service.enabled=false
        openShop()
        assertEquals(Material.BARRIER,player.openInventory.topInventory.getItem(20)?.type)
        click(player.openInventory,20)
        server.scheduler.performTicks(1)
        assertEquals("SHOP",PlainTextComponentSerializer.plainText().serialize(player.openInventory.title()))
        assertEquals(Material.BARRIER,player.openInventory.topInventory.getItem(20)?.type)
    }

    @Test fun `category paginates and back returns to the main menu`() {
        openShop();click(player.openInventory,20);server.scheduler.performTicks(1)
        val category=player.openInventory.topInventory
        assertEquals(Material.APPLE,category.getItem(0)?.type)
        assertEquals(Material.ARROW,category.getItem(53)?.type)
        click(player.openInventory,53);server.scheduler.performTicks(1)
        assertEquals(Material.APPLE,player.openInventory.topInventory.getItem(0)?.type)
        assertTrue(player.openInventory.topInventory.getItem(0)!!.itemMeta.displayName().toString().contains("Item 45"))
        click(player.openInventory,49);server.scheduler.performTicks(1)
        assertEquals("SHOP",PlainTextComponentSerializer.plainText().serialize(player.openInventory.title()))
        assertEquals(Material.COOKED_BEEF,player.openInventory.topInventory.getItem(20)?.type)
    }

    private fun openShop(){module.onCommand(player,ShopCommand(),"shop", emptyArray());server.scheduler.performTicks(1)}
    private fun click(view:org.bukkit.inventory.InventoryView,slot:Int) {
        val event=InventoryClickEvent(view,InventoryType.SlotType.CONTAINER,slot,ClickType.LEFT,InventoryAction.PICKUP_ALL)
        Bukkit.getPluginManager().callEvent(event);assertTrue(event.isCancelled)
    }

    private class FakeShopService:ShopService {
        var enabled=true
        override fun loadStates(categoryIds:Set<String>)=CompletableFuture.completedFuture(categoryIds.associateWith { ShopCategoryState(it,enabled,null,Instant.EPOCH) })
        override fun isEnabled(categoryId:String)=CompletableFuture.completedFuture(enabled)
        override fun setEnabled(categoryId:String,enabled:Boolean,updatedBy:UUID)=CompletableFuture.completedFuture(ShopStateUpdate(ShopCategoryState(categoryId,enabled,updatedBy,Instant.EPOCH),true))
    }
    private class FakeEventBus:EventBus {
        override fun publish(event:NetworkEvent)=CompletableFuture.completedFuture(Unit)
        override fun subscribe(eventType:EventType,listener:EventListener)=EventSubscription {}
        override fun close()=Unit
    }
}

open class ShopGuiTestPlugin:JavaPlugin()
private class ShopCommand:Command("shop") { override fun execute(sender:org.bukkit.command.CommandSender,label:String,args:Array<out String>)=true }
