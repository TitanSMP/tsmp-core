package de.titansmp.paper.shop

import de.titansmp.api.economy.EconomyException
import de.titansmp.api.economy.EconomyReason
import de.titansmp.api.economy.EconomyService
import de.titansmp.api.economy.Money
import de.titansmp.api.event.EventBus
import de.titansmp.api.event.EventListener
import de.titansmp.api.shop.ShopCategoryStateChanged
import de.titansmp.api.shop.ShopService
import de.titansmp.core.config.ShopCategoryConfig
import de.titansmp.core.config.ShopConfig
import de.titansmp.core.config.ShopItemConfig
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletionException
import java.util.logging.Level

class AdminShopPaperModule(
    private val plugin: JavaPlugin,
    private val service: ShopService,
    private val economy: EconomyService,
    private val events: EventBus,
    private val config: ShopConfig,
    private val serverId: String,
) : Listener, CommandExecutor, TabCompleter, AutoCloseable {
    private val categories = config.categories.associateBy { it.id }
    private val stateCache = ConcurrentHashMap<String, Boolean>()
    private val busy = ConcurrentHashMap.newKeySet<UUID>()
    private val subscriptions = mutableListOf<AutoCloseable>()

    init {
        validate(config)
    }

    companion object {
        fun validate(config: ShopConfig) {
        config.categories.forEach { category ->
            require(Material.matchMaterial(category.icon) != null) { "Invalid shop category icon '${category.icon}' for '${category.id}'" }
            category.items.forEach { item ->
                val material = Material.matchMaterial(item.material)
                    ?: throw IllegalArgumentException("Invalid shop material '${item.material}' for '${category.id}.${item.id}'")
                require(material.isItem) { "Shop material '${item.material}' is not an item for '${category.id}.${item.id}'" }
            }
        }
        }
    }

    fun register() {
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.getCommand("shop")?.setExecutor(this) ?: error("Missing /shop command in plugin.yml")
        plugin.getCommand("shopadmin")?.setExecutor(this) ?: error("Missing /shopadmin command in plugin.yml")
        plugin.getCommand("shopadmin")?.tabCompleter = this
        subscriptions += events.subscribe(ShopCategoryStateChanged.TYPE, EventListener { envelope ->
            val event = envelope.event as ShopCategoryStateChanged
            if (event.categoryId in categories) {
                stateCache[event.categoryId] = event.enabled
                refreshOpenViews(event.categoryId)
            }
        })
        refreshCache()
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (command.name.equals("shop", true)) {
            if (sender !is Player) { sender.sendMessage("This command can only be used in game."); return true }
            if (!sender.hasPermission("titansmp.shop.use")) { sender.sendMessage(Component.text("You do not have permission to use the shop.", NamedTextColor.RED)); return true }
            if (!config.enabled) { sender.sendMessage(Component.text("The shop is currently unavailable.", NamedTextColor.RED)); return true }
            openMain(sender)
            return true
        }
        if (!sender.hasPermission("titansmp.shop.admin")) { sender.sendMessage(Component.text("You do not have permission to administer the shop.", NamedTextColor.RED)); return true }
        if (args.firstOrNull()?.equals("categories", true) == true && args.size == 1) {
            service.loadStates(categories.keys).whenComplete { states, failure -> onMain {
                if (failure != null) sender.sendMessage(Component.text("Could not load shop state: ${message(failure)}", NamedTextColor.RED))
                else categories.keys.forEach { id -> sender.sendMessage(Component.text("$id: ${if (states.getValue(id).enabled) "enabled" else "disabled"}", if (states.getValue(id).enabled) NamedTextColor.GREEN else NamedTextColor.RED)) }
            } }
            return true
        }
        if (args.size != 3 || !args[0].equals("category", true)) {
            sender.sendMessage(Component.text("Usage: /shopadmin category <category> <enable|disable|status>", NamedTextColor.YELLOW)); return true
        }
        if (sender !is Player) { sender.sendMessage("This command can only be used in game."); return true }
        val category = categories[args[1].lowercase()]
        if (category == null) { sender.sendMessage(Component.text("Unknown shop category '${args[1]}'.", NamedTextColor.RED)); return true }
        val actor = sender.uniqueId
        when (args[2].lowercase()) {
            "enable", "disable" -> {
                val enabled = args[2].equals("enable", true)
                service.setEnabled(category.id, enabled, actor).whenComplete { update, failure -> onMain {
                    if (failure != null) sender.sendMessage(Component.text("Shop state was not saved: ${message(failure)}", NamedTextColor.RED))
                    else {
                        stateCache[category.id] = enabled
                        val result = if (update.broadcasted) "Updated network-wide." else "Saved to MariaDB; Redis is unavailable and other servers will refresh from the database."
                        sender.sendMessage(Component.text("${category.displayName} is ${if (enabled) "enabled" else "disabled"}. $result", if (update.broadcasted) NamedTextColor.GREEN else NamedTextColor.YELLOW))
                    }
                } }
            }
            "status" -> service.isEnabled(category.id).whenComplete { enabled, failure -> onMain {
                if (failure != null) sender.sendMessage(Component.text("Could not load shop state: ${message(failure)}", NamedTextColor.RED))
                else { stateCache[category.id] = enabled; sender.sendMessage(Component.text("${category.displayName}: ${if (enabled) "enabled" else "disabled"}", if (enabled) NamedTextColor.GREEN else NamedTextColor.RED)) }
            } }
            else -> sender.sendMessage(Component.text("Use enable, disable, or status.", NamedTextColor.YELLOW))
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> = when (args.size) {
        1 -> listOf("category", "categories").filter { it.startsWith(args[0], true) }
        2 -> if (args[0].equals("category", true)) categories.keys.filter { it.startsWith(args[1], true) } else emptyList()
        3 -> if (args[0].equals("category", true) && args[1].lowercase() in categories) listOf("enable", "disable", "status").filter { it.startsWith(args[2], true) } else emptyList()
        else -> emptyList()
    }

    private fun openMain(player: Player) {
        service.loadStates(categories.keys).whenComplete { states, failure -> onMain {
            if (!player.isOnline) return@onMain
            if (failure != null) { error(player, "Could not load shop categories: ${message(failure)}"); return@onMain }
            stateCache.putAll(states.mapValues { it.value.enabled })
            val holder = ShopHolder(Screen.MAIN)
            val inv = Bukkit.createInventory(holder, 54, Component.text("SHOP", NamedTextColor.DARK_GREEN)); holder.inv = inv
            config.categories.forEach { category -> inv.setItem(category.slot, categoryIcon(category, states[category.id]?.enabled ?: true)) }
            inv.setItem(49, button(Material.BARRIER, "Close Shop"))
            player.openInventory(inv)
        } }
    }

    private fun openCategory(player: Player, categoryId: String, page: Int) {
        val category = categories[categoryId] ?: return error(player, "That shop category no longer exists.")
        service.isEnabled(categoryId).whenComplete { enabled, failure -> onMain {
            if (!player.isOnline) return@onMain
            if (failure != null) return@onMain error(player, "Could not verify category state: ${message(failure)}")
            stateCache[categoryId] = enabled
            if (!enabled) { error(player, "This category is currently disabled."); openMain(player); return@onMain }
            val pages = pages(category).ifEmpty { listOf(emptyList()) }
            val safePage = page.coerceIn(0, pages.lastIndex)
            val holder = ShopHolder(Screen.CATEGORY, categoryId=categoryId, page=safePage)
            val inv = Bukkit.createInventory(holder, 54, Component.text("SHOP - ${category.displayName.uppercase()}", NamedTextColor.DARK_GREEN)); holder.inv=inv
            pages[safePage].forEach { item -> inv.setItem(item.slot, displayItem(item)) }
            inv.setItem(49, button(Material.ARROW, "Back", "Return to categories"))
            if (safePage > 0) inv.setItem(45, button(Material.ARROW, "Previous Page"))
            if (safePage < pages.lastIndex) inv.setItem(53, button(Material.ARROW, "Next Page"))
            player.openInventory(inv)
        } }
    }

    private fun categoryIcon(category: ShopCategoryConfig, enabled: Boolean) = if (enabled) button(Material.matchMaterial(category.icon)!!, category.displayName)
        else button(Material.BARRIER, "${category.displayName} - Disabled", "This category is currently disabled.")

    private fun displayItem(item: ShopItemConfig): ItemStack = ItemStack(Material.matchMaterial(item.material)!!).apply {
        amount = item.amount.coerceAtMost(type.maxStackSize)
        itemMeta = itemMeta?.also { meta ->
            meta.displayName(Component.text(item.displayName, NamedTextColor.YELLOW))
            meta.lore(listOf(
                Component.text("Buy: ${item.buyPrice?.format() ?: "Unavailable"} TitanCoins", NamedTextColor.GREEN),
                Component.text("Sell: ${item.sellPrice?.format() ?: "Unavailable"} TitanCoins", NamedTextColor.GOLD),
                Component.text("Left Click → Choose quantity", NamedTextColor.GRAY),
            ))
        }
    }

    private fun openAmounts(player: Player, category: ShopCategoryConfig, item: ShopItemConfig, page: Int) {
        val holder = ShopHolder(Screen.AMOUNTS, categoryId=category.id, itemId=item.id, page=page)
        val inv = Bukkit.createInventory(holder, 27, Component.text("Choose Quantity", NamedTextColor.DARK_GREEN)); holder.inv=inv
        listOf(1,16,32,64).forEachIndexed { index, quantity ->
            val amount = (item.amount.toLong() * quantity).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val icon = ItemStack(Material.matchMaterial(item.material)!!).apply {
                this.amount = minOf(amount, type.maxStackSize)
                itemMeta = itemMeta?.also { it.displayName(Component.text("$quantity unit${if (quantity == 1) "" else "s"}", NamedTextColor.YELLOW)); it.lore(listOf(Component.text("Total items: $amount", NamedTextColor.GRAY), Component.text("Click to choose buy or sell", NamedTextColor.GRAY))) }
            }
            inv.setItem(listOf(10,12,14,16)[index], icon)
        }
        inv.setItem(22, button(Material.ARROW, "Back")); player.openInventory(inv)
    }

    private fun openActionChoice(player: Player, category: ShopCategoryConfig, item: ShopItemConfig, page: Int, quantity: Int) {
        val holder=ShopHolder(Screen.ACTION_CHOICE,categoryId=category.id,itemId=item.id,page=page,quantity=quantity)
        val inv=Bukkit.createInventory(holder,27,Component.text("Buy or Sell",NamedTextColor.DARK_GREEN));holder.inv=inv
        val count=item.amount*quantity
        inv.setItem(13,displayItem(item).also { it.amount=minOf(count,it.type.maxStackSize) })
        item.buyPrice?.let { inv.setItem(11,button(Material.LIME_CONCRETE,"Buy $count ${item.displayName}","Price: ${scaled(it,quantity).format()} TitanCoins")) }
        item.sellPrice?.let { inv.setItem(15,button(Material.GOLD_INGOT,"Sell $count ${item.displayName}","Receive: ${scaled(it,quantity).format()} TitanCoins")) }
        inv.setItem(22,button(Material.ARROW,"Back"));player.openInventory(inv)
    }

    private fun openConfirm(player: Player, category: ShopCategoryConfig, item: ShopItemConfig, page: Int, quantity: Int, buying: Boolean) {
        val holder=ShopHolder(Screen.CONFIRM,categoryId=category.id,itemId=item.id,page=page,quantity=quantity,buying=buying)
        val inv=Bukkit.createInventory(holder,27,Component.text("Confirm ${if(buying) "Purchase" else "Sale"}",NamedTextColor.DARK_GREEN));holder.inv=inv
        val count=item.amount*quantity; val unitPrice=if(buying)item.buyPrice else item.sellPrice
        inv.setItem(13,displayItem(item).also { it.amount=minOf(count,it.type.maxStackSize) })
        inv.setItem(11,button(Material.LIME_CONCRETE,"Confirm", "${if(buying) "Pay" else "Receive"} ${scaled(requireNotNull(unitPrice),quantity).format()} TitanCoins"))
        inv.setItem(15,button(Material.RED_CONCRETE,"Cancel"));player.openInventory(inv)
    }

    @EventHandler fun onClick(event: InventoryClickEvent) {
        val player=event.whoClicked as? Player ?: return
        if(player.uniqueId in busy) { event.isCancelled=true;return }
        val holder=event.view.topInventory.holder as? ShopHolder ?: return
        event.isCancelled=true
        if(event.clickedInventory !== event.view.topInventory || event.currentItem == null || event.currentItem!!.type.isAir) return
        val category=holder.categoryId?.let(categories::get)
        val item=if(category!=null) category.items.firstOrNull { it.id==holder.itemId } else null
        when(holder.screen) {
            Screen.MAIN -> {
                if(event.rawSlot==49) { player.closeInventory();return }
                val categoryId=config.categories.firstOrNull { it.slot==event.rawSlot }?.id ?: return
                openCategory(player,categoryId,0)
            }
            Screen.CATEGORY -> {
                if(category==null) return
                when(event.rawSlot) {
                    49 -> openMain(player)
                    45 -> openCategory(player,category.id,holder.page-1)
                    53 -> openCategory(player,category.id,holder.page+1)
                    else -> {
                        val pageItems=pages(category).getOrNull(holder.page).orEmpty()
                        val selected=pageItems.firstOrNull { it.slot==event.rawSlot } ?: return
                        service.isEnabled(category.id).whenComplete { enabled, failure -> onMain {
                            if(failure!=null) error(player,"Could not verify category state: ${message(failure)}")
                            else if(!enabled) { stateCache[category.id]=false; error(player,"This category is currently disabled.");openMain(player) }
                            else openAmounts(player,category,selected,holder.page)
                        } }
                    }
                }
            }
            Screen.AMOUNTS -> {
                if(category==null||item==null) return
                if(event.rawSlot==22) { openCategory(player,category.id,holder.page);return }
                val quantity=listOf(10 to 1,12 to 16,14 to 32,16 to 64).firstOrNull { it.first==event.rawSlot }?.second ?: return
                openActionChoice(player,category,item,holder.page,quantity)
            }
            Screen.ACTION_CHOICE -> {
                if(category==null||item==null) return
                if(event.rawSlot==22) { openAmounts(player,category,item,holder.page);return }
                if(event.rawSlot==11&&item.buyPrice!=null) openConfirm(player,category,item,holder.page,holder.quantity,true)
                if(event.rawSlot==15&&item.sellPrice!=null) openConfirm(player,category,item,holder.page,holder.quantity,false)
            }
            Screen.CONFIRM -> {
                if(category==null||item==null) return
                if(event.rawSlot==15) { openActionChoice(player,category,item,holder.page,holder.quantity);return }
                if(event.rawSlot==11) transact(player,category,item,holder.page,holder.quantity,holder.buying)
            }
        }
    }

    @EventHandler fun onDrag(event: InventoryDragEvent) { if(event.view.topInventory.holder is ShopHolder || (event.whoClicked as? Player)?.uniqueId in busy) event.isCancelled=true }
    @EventHandler fun onDrop(event: PlayerDropItemEvent) { if(event.player.uniqueId in busy) event.isCancelled=true }
    @EventHandler fun onQuit(event: PlayerQuitEvent) { busy.remove(event.player.uniqueId) }

    private fun transact(player:Player,category:ShopCategoryConfig,item:ShopItemConfig,page:Int,quantity:Int,buying:Boolean) {
        if(!busy.add(player.uniqueId)) return error(player,"A shop transaction is already in progress.")
        service.isEnabled(category.id).whenComplete { enabled,stateFailure -> onMain {
            if(stateFailure!=null) { busy.remove(player.uniqueId);error(player,"Could not verify category state: ${message(stateFailure)}");return@onMain }
            if(!enabled) { stateCache[category.id]=false;busy.remove(player.uniqueId);error(player,"This category is currently disabled.");openMain(player);return@onMain }
            val stillConfigured=categories[category.id]?.items?.firstOrNull { it.id==item.id }
            if(stillConfigured!=item) { busy.remove(player.uniqueId);error(player,"This shop item is no longer configured.");return@onMain }
            val count=Math.multiplyExact(item.amount,quantity)
            val material=Material.matchMaterial(item.material)!!
            val total=scaled(requireNotNull(if(buying)item.buyPrice else item.sellPrice),quantity)
            if(buying) {
                if(!hasSpace(player,material,count)) { busy.remove(player.uniqueId);error(player,"You don't have enough inventory space.");return@onMain }
                val paid=if(total.isZero()) java.util.concurrent.CompletableFuture.completedFuture(Unit) else economy.withdraw(player.uniqueId,total,EconomyReason.SHOP_PURCHASE,null,serverId).thenApply { Unit }
                paid.whenComplete { _,failure -> onMain {
                    if(failure!=null) { busy.remove(player.uniqueId); economyError(player,failure);return@onMain }
                    if(!player.isOnline||!hasSpace(player,material,count)) {
                        if(total.isZero()) { busy.remove(player.uniqueId);error(player,"Your inventory changed before delivery.") }
                        else economy.deposit(player.uniqueId,total,EconomyReason.SHOP_PURCHASE,null,serverId).whenComplete { _,refundFailure -> onMain { busy.remove(player.uniqueId);error(player,if(refundFailure==null) "Purchase cancelled; your TitanCoins were refunded." else "Purchase delivery failed and refund needs staff attention. Please contact staff.") } }
                        return@onMain
                    }
                    give(player,material,count)
                    busy.remove(player.uniqueId);player.playSound(player.location,Sound.ENTITY_EXPERIENCE_ORB_PICKUP,0.7f,1.2f)
                    player.sendMessage(Component.text("Purchased $count ${item.displayName} for ${total.format()} TitanCoins.",NamedTextColor.GREEN))
                } }
            } else {
                if(!removeExact(player,material,count)) { busy.remove(player.uniqueId);error(player,"You don't have enough ${item.displayName}.");return@onMain }
                val credited=if(total.isZero()) java.util.concurrent.CompletableFuture.completedFuture(Unit) else economy.deposit(player.uniqueId,total,EconomyReason.SHOP_SELL,null,serverId).thenApply { Unit }
                credited.whenComplete { _,failure -> onMain {
                    if(failure!=null) {
                        give(player,material,count);busy.remove(player.uniqueId);economyError(player,failure)
                    } else {
                        busy.remove(player.uniqueId);player.playSound(player.location,Sound.ENTITY_EXPERIENCE_ORB_PICKUP,0.7f,1.1f)
                        player.sendMessage(Component.text("Sold $count ${item.displayName} for ${total.format()} TitanCoins.",NamedTextColor.GREEN))
                    }
                } }
            }
        } }
    }

    private fun hasSpace(player:Player,material:Material,amount:Int):Boolean {
        var remaining=amount
        player.inventory.storageContents.forEach { stack -> if(stack?.type==material) remaining-=material.maxStackSize-stack.amount }
        val empty=player.inventory.storageContents.count { it==null||it.type.isAir }
        remaining-=empty*material.maxStackSize
        return remaining<=0
    }
    private fun give(player:Player,material:Material,amount:Int) { var left=amount;while(left>0) { val part=minOf(left,material.maxStackSize);player.inventory.addItem(ItemStack(material,part));left-=part } }
    private fun removeExact(player:Player,material:Material,amount:Int):Boolean {
        val contents=player.inventory.storageContents
        if(contents.filterNotNull().filter { it.type==material }.sumOf { it.amount }<amount)return false
        var left=amount
        contents.indices.forEach { i -> val stack=contents[i];if(left>0&&stack?.type==material){val take=minOf(left,stack.amount);stack.amount-=take;left-=take;if(stack.amount<=0)contents[i]=null} }
        player.inventory.storageContents=contents
        return left==0
    }
    private fun scaled(price:Money,quantity:Int)=Money.ofMinor(Math.multiplyExact(price.minorUnits,quantity.toLong()))
    private fun pages(category:ShopCategoryConfig)=category.items.sortedBy { it.id }.chunked(45).map { page -> page.sortedBy { it.slot } }
    private fun refreshCache() { service.loadStates(categories.keys).whenComplete { states,failure -> if(failure!=null) plugin.logger.log(Level.WARNING,"Shop category state load failed; state is reloaded on interaction",failure) else stateCache.putAll(states.mapValues { it.value.enabled }) } }
    private fun refreshOpenViews(changedId:String) = service.loadStates(categories.keys).whenComplete { states,failure ->
        if(failure!=null) { plugin.logger.log(Level.WARNING,"Could not refresh an open shop after a category change",failure);return@whenComplete }
        onMain {
            stateCache.putAll(states.mapValues { it.value.enabled })
            val category=categories[changedId] ?: return@onMain
            Bukkit.getOnlinePlayers().forEach { player ->
                val holder=player.openInventory.topInventory.holder as? ShopHolder ?: return@forEach
                when(holder.screen) {
                    Screen.MAIN -> player.openInventory.topInventory.setItem(category.slot,categoryIcon(category,states[changedId]?.enabled ?: true))
                    Screen.CATEGORY -> if(holder.categoryId==changedId && states[changedId]?.enabled==false) openMain(player)
                    else -> Unit
                }
            }
        }
    }
    private fun button(material:Material,name:String,vararg lore:String)=ItemStack(material).also { it.itemMeta=it.itemMeta?.also { meta -> meta.displayName(Component.text(name,NamedTextColor.YELLOW));meta.lore(lore.map { line->Component.text(line,NamedTextColor.GRAY) }) } }
    private fun error(player:Player,text:String) { player.sendMessage(Component.text(text,NamedTextColor.RED));player.playSound(player.location,Sound.BLOCK_NOTE_BLOCK_BASS,0.6f,0.8f) }
    private fun economyError(player:Player,failure:Throwable) { val cause=unwrap(failure);error(player,when(cause){is EconomyException->cause.message?:"Economy transaction failed.";else->"Economy transaction failed. Please try again."}) }
    private fun message(failure:Throwable)=unwrap(failure).message?:"unknown error"
    private fun unwrap(t:Throwable):Throwable { var result=t;while(result is CompletionException&&result.cause!=null)result=result.cause!!;return result }
    private fun onMain(block:()->Unit) { Bukkit.getScheduler().runTask(plugin,Runnable(block)) }
    override fun close() { subscriptions.forEach { runCatching { it.close() } };subscriptions.clear() }

    private enum class Screen { MAIN,CATEGORY,AMOUNTS,ACTION_CHOICE,CONFIRM }
    private class ShopHolder(val screen:Screen,val categoryId:String?=null,val itemId:String?=null,val page:Int=0,val quantity:Int=0,val buying:Boolean=false):InventoryHolder {
        lateinit var inv:Inventory
        override fun getInventory()=inv
    }
}
