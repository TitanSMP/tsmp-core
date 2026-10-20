package de.titansmp.paper.auctionhouse

import de.titansmp.api.auctionhouse.*
import de.titansmp.api.economy.EconomyException
import de.titansmp.api.economy.EconomyReason
import de.titansmp.api.economy.EconomyService
import de.titansmp.api.economy.Money
import de.titansmp.auctionhouse.AuctionLimitResolver
import de.titansmp.core.config.AuctionHouseConfig
import de.titansmp.paper.serialization.PaperItemStackCodec
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataType
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap

/** Paper command, complete ItemStack serialization, chat input, and inventory presentation adapter. */
class AuctionHousePaperModule(
    private val plugin: JavaPlugin,
    private val service: AuctionHouseService,
    private val economy: EconomyService,
    private val config: AuctionHouseConfig,
    private val serverId: String,
    private val permissions: (UUID) -> java.util.concurrent.CompletionStage<Set<String>>,
) : Listener, CommandExecutor, AutoCloseable {
    private val sortByPlayer = ConcurrentHashMap<UUID,AuctionSort>()
    private val searching = ConcurrentHashMap<UUID,Long>()
    private val lockedSellers = ConcurrentHashMap.newKeySet<UUID>()
    private val purchasing = ConcurrentHashMap.newKeySet<UUID>()
    private val claiming = ConcurrentHashMap.newKeySet<UUID>()
    private val searchTimers = ConcurrentHashMap<UUID,BukkitTask>()
    private val titlePrefix = "Auction House"
    private val claimKey = NamespacedKey(plugin,"auction_claim_id")

    fun register() {
        Bukkit.getPluginManager().registerEvents(this,plugin)
        val command=plugin.getCommand("ah")
        command?.setAliases(config.aliases)
        command?.setExecutor(this)
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin,Runnable { service.expireDueAuctions().exceptionally { plugin.logger.severe("Auction expiration failed: ${it.message}");0 } },20L,1200L)
        (service as? de.titansmp.auctionhouse.DefaultAuctionHouseService)?.recoverPendingPurchases()
            ?.exceptionally { plugin.logger.severe("Pending auction purchase recovery failed: ${it.message}");0 }
    }

    override fun close() { searchTimers.values.forEach(BukkitTask::cancel);searchTimers.clear();searching.clear() }

    override fun onCommand(sender:CommandSender,command:Command,label:String,args:Array<out String>):Boolean {
        if(sender !is Player) { sender.sendMessage("This command can only be used in-game.");return true }
        if(args.isEmpty()) { openMain(sender,0);return true }
        when(args[0].lowercase()) {
            "sell" -> sell(sender,args)
            "expired" -> openItems(sender,0,AuctionItemKind.EXPIRED)
            "purchased" -> openItems(sender,0,AuctionItemKind.PURCHASED)
            "cancel" -> {
                val id=args.getOrNull(1)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                if(id==null) sender.sendMessage(Component.text("Usage: /ah cancel <auction-id>",NamedTextColor.RED)) else cancel(sender,id)
            }
            "info" -> showInfo(sender)
            else -> sender.sendMessage(Component.text("Usage: /ah [sell <price>|expired|purchased|cancel <auction-id>|info]",NamedTextColor.YELLOW))
        }
        return true
    }

    private fun sell(player:Player,args:Array<out String>) {
        val price=try { Money.of(args.getOrNull(1)?:"") } catch(_:RuntimeException) { player.sendMessage(Component.text("Usage: /ah sell <positive price>",NamedTextColor.RED));return }
        if(!price.isPositive()) { player.sendMessage(Component.text("Price must be greater than zero.",NamedTextColor.RED));return }
        val hand=player.inventory.itemInMainHand
        if(hand.type.isAir || hand.amount<=0) { player.sendMessage(Component.text("There is no item in your main hand.",NamedTextColor.RED));return }
        val item=hand.clone()
        val payload=PaperItemStackCodec.encode(item) ?: run { player.sendMessage(Component.text("That item cannot be listed.",NamedTextColor.RED));return }
        val stored=StoredAuctionItem(payload,searchText(item))
        if(!lockedSellers.add(player.uniqueId)) { player.sendMessage(Component.text("Your previous listing is still being processed.",NamedTextColor.RED));return }
        permissions(player.uniqueId).whenComplete { nodes, permissionFailure -> Bukkit.getScheduler().runTask(plugin,Runnable {
            if(!player.isOnline) { lockedSellers.remove(player.uniqueId);return@Runnable }
            if(permissionFailure!=null || "titansmp.auction.sell" !in nodes) { lockedSellers.remove(player.uniqueId);player.sendMessage(Component.text("You do not have permission to sell auctions.",NamedTextColor.RED));return@Runnable }
            if(player.inventory.itemInMainHand.type.isAir || !PaperItemStackCodec.encode(player.inventory.itemInMainHand).contentEquals(payload)) {
                lockedSellers.remove(player.uniqueId);player.sendMessage(Component.text("Your held item changed; nothing was listed.",NamedTextColor.RED));return@Runnable
            }
            player.inventory.setItemInMainHand(ItemStack(Material.AIR))
            if(!player.inventory.itemInMainHand.type.isAir) {
                lockedSellers.remove(player.uniqueId)
                player.sendMessage(Component.text("The item could not be removed from your hand; nothing was listed.",NamedTextColor.RED))
                return@Runnable
            }
            val fee=fee(price)
            val charge=if(fee.isPositive()) economy.withdraw(player.uniqueId,fee,EconomyReason.AUCTION_HOUSE,null,serverId) else java.util.concurrent.CompletableFuture.completedFuture(null)
            charge.whenComplete { _, feeFailure ->
                if(feeFailure!=null) { restore(player,item);player.sendMessage(Component.text("You do not have enough TitanCoins to pay the listing fee.",NamedTextColor.RED));return@whenComplete }
                val limit=AuctionLimitResolver.resolve(nodes,config.defaultAuctionLimit)
                val expires=Instant.now().plus(Duration.ofHours(config.expirationHours))
                service.createAuction(player.uniqueId,stored,price,limit,expires).whenComplete { auction,failure ->
                    if(failure!=null) {
                        if(fee.isPositive()) economy.deposit(player.uniqueId,fee,EconomyReason.AUCTION_HOUSE,null,serverId).whenComplete { _,refundFailure ->
                            if(refundFailure!=null) plugin.logger.severe("Could not refund listing fee for ${player.uniqueId}: ${refundFailure.message}")
                        }
                        restore(player,item)
                        player.sendMessage(Component.text(unwrap(failure).message?:"Auction creation failed.",NamedTextColor.RED))
                    } else {
                        lockedSellers.remove(player.uniqueId)
                        player.sendMessage(Component.text("Auction created successfully. ID: ${auction.auctionId}",NamedTextColor.GREEN))
                    }
                }
            }
        }) }
    }

    private fun fee(price:Money):Money {
        if(config.sellFeePercent==0) return Money.ZERO
        val minor=BigInteger.valueOf(price.minorUnits).multiply(BigInteger.valueOf(config.sellFeePercent.toLong())).divide(BigInteger.valueOf(100)).longValueExact()
        return Money.ofMinor(minor)
    }

    private fun restore(player:Player,item:ItemStack) {
        Bukkit.getScheduler().runTask(plugin,Runnable {
            if(player.isOnline && canFit(player,item)) {
                if(player.inventory.itemInMainHand.type.isAir) player.inventory.setItemInMainHand(item)
                else player.inventory.addItem(item)
                lockedSellers.remove(player.uniqueId)
            } else {
                val bytes=PaperItemStackCodec.encode(item)
                if(bytes==null) { plugin.logger.severe("Could not serialize returned auction item for ${player.uniqueId}");lockedSellers.remove(player.uniqueId);return@Runnable }
                service.storeReturnedItem(player.uniqueId,StoredAuctionItem(bytes,searchText(item))).whenComplete { _,failure ->
                    if(failure!=null) plugin.logger.severe("Could not persist a returned auction item for ${player.uniqueId}: ${failure.message}")
                    else if(player.isOnline) Bukkit.getScheduler().runTask(plugin,Runnable { player.sendMessage(Component.text("Your item was saved under /ah expired.",NamedTextColor.YELLOW)) })
                    lockedSellers.remove(player.uniqueId)
                }
            }
        })
    }

    private fun cancel(player:Player,id:UUID) {
        service.cancelAuction(id,player.uniqueId).whenComplete { _,failure -> Bukkit.getScheduler().runTask(plugin,Runnable {
            if(failure==null) player.sendMessage(Component.text("Auction cancelled. Your item is available under /ah expired.",NamedTextColor.GREEN))
            else player.sendMessage(Component.text(unwrap(failure).message?:"Auction cancellation failed.",NamedTextColor.RED))
        }) }
    }

    private fun openMain(player:Player,page:Int,query:String?=null,sort:AuctionSort=sortByPlayer[player.uniqueId]?:AuctionSort.NEWEST) {
        service.listAuctions(player.uniqueId,query,sort,page,config.itemsPerPage).whenComplete { result,failure -> Bukkit.getScheduler().runTask(plugin,Runnable {
            if(!player.isOnline) return@Runnable
            if(failure!=null) { player.sendMessage(Component.text("Auction House could not be loaded.",NamedTextColor.RED));return@Runnable }
            val actualPage=page.coerceIn(0,result.totalPages-1)
            if(actualPage!=page) { openMain(player,actualPage,query,sort);return@Runnable }
            val holder=AuctionGuiHolder(player.uniqueId,Screen.MAIN,page,query,sort)
            val inv=holder.create("$titlePrefix (${page+1}/${result.totalPages})",54)
            result.items.forEachIndexed { index,auction -> inv.setItem(index,listing(auction,query));holder.actions[index]={openConfirm(player,auction)} }
            if(result.items.isEmpty()) inv.setItem(22,button(Material.BARRIER,"No auctions found.","Try another search or check back later."))
            inv.setItem(45,button(Material.CHEST,"Expired Items","Click to view your expired and cancelled items.").also { holder.actions[45]={openItems(player,0,AuctionItemKind.EXPIRED)} })
            inv.setItem(46,button(Material.BARREL,"Purchased Items","Claim items you bought.").also { holder.actions[46]={openItems(player,0,AuctionItemKind.PURCHASED)} })
            inv.setItem(47,button(Material.COMPASS,"Search",*(if(query==null) listOf("Click to search listings.") else listOf("Current: $query","Click to change search.")).toTypedArray()).also { holder.actions[47]={startSearch(player,page,sort)} })
            inv.setItem(48,button(if(page>0) Material.ARROW else Material.GRAY_STAINED_GLASS_PANE,if(page>0)"Previous Page" else "Previous Page","Page ${page.coerceAtLeast(0)}").also { if(page>0) holder.actions[48]={openMain(player,page-1,query,sort)} })
            inv.setItem(49,button(Material.BOOK,"Auction House Info","Buy and sell items from players across the network.","Commands: /ah, /ah sell <price>, /ah expired, /ah purchased, /ah cancel <id>","Click an auction to review it before purchase.","Search: ${query?:"none"}"))
            inv.setItem(50,button(if(page+1<result.totalPages) Material.ARROW else Material.GRAY_STAINED_GLASS_PANE,"Next Page","Page ${page+2}").also { if(page+1<result.totalPages) holder.actions[50]={openMain(player,page+1,query,sort)} })
            inv.setItem(51,button(Material.GRAY_STAINED_GLASS_PANE,"Placeholder",""))
            inv.setItem(52,button(Material.HOPPER,"Sort","Current: ${sort.label()}","Click to change.").also { holder.actions[52]={val next=AuctionSort.entries[(AuctionSort.entries.indexOf(sort)+1)%AuctionSort.entries.size];sortByPlayer[player.uniqueId]=next;openMain(player,0,query,next)} })
            inv.setItem(53,button(Material.PLAYER_HEAD,"Your Auctions","View and manage your active listings.").also { holder.actions[53]={openYourAuctions(player,0)} })
            player.openInventory(inv)
        }) }
    }

    private fun openConfirm(player:Player,auction:Auction) {
        val holder=AuctionGuiHolder(player.uniqueId,Screen.CONFIRM,0,null,AuctionSort.NEWEST)
        val inv=holder.create("Confirm Purchase",27)
        inv.setItem(13,listing(auction,null))
        inv.setItem(11,button(Material.LIME_CONCRETE,"CONFIRM PURCHASE","Price: ${auction.price.format()} TitanCoins","Seller: ${auction.sellerName}","Click to confirm this purchase.").also { holder.actions[11]=action@{
            if(!purchasing.add(player.uniqueId)) return@action
            player.closeInventory();player.sendMessage(Component.text("Processing your purchase...",NamedTextColor.YELLOW))
            service.purchaseAuction(auction.auctionId,player.uniqueId,serverId).whenComplete { _,failure -> Bukkit.getScheduler().runTask(plugin,Runnable { purchasing.remove(player.uniqueId);if(player.isOnline) {
                if(failure==null) { player.sendMessage(Component.text("Auction purchased successfully. Your item has been added to Purchased Items.",NamedTextColor.GREEN));openItems(player,0,AuctionItemKind.PURCHASED) }
                else player.sendMessage(Component.text(unwrap(failure).message?:"Purchase failed.",NamedTextColor.RED))
            } }) }
        } })
        inv.setItem(15,button(Material.RED_CONCRETE,"CANCEL","Return to the Auction House.").also { holder.actions[15]={openMain(player,0)} })
        player.openInventory(inv)
    }

    private fun openYourAuctions(player:Player,page:Int) {
        service.listPlayerAuctions(player.uniqueId,page,config.itemsPerPage).whenComplete { rows,failure -> Bukkit.getScheduler().runTask(plugin,Runnable {
            if(!player.isOnline)return@Runnable
            if(failure!=null){player.sendMessage(Component.text("Your auctions could not be loaded.",NamedTextColor.RED));return@Runnable}
            val holder=AuctionGuiHolder(player.uniqueId,Screen.YOURS,page,null,AuctionSort.YOUR_AUCTIONS);val inv=holder.create("Your Auctions",54)
            rows.forEachIndexed { i,a -> inv.setItem(i,listing(a,null,"ID: ${a.auctionId}"));holder.actions[i]={confirmCancel(player,a)} }
            inv.setItem(49,button(Material.BOOK,"Your Auctions","Click a listing to review cancellation.","Auction IDs are also shown in /ah cancel <id>."))
            inv.setItem(45,button(if(page>0)Material.ARROW else Material.GRAY_STAINED_GLASS_PANE,"Previous Page").also { if(page>0)holder.actions[45]={openYourAuctions(player,page-1)} })
            inv.setItem(48,button(Material.ARROW,"Back","Return to the Auction House.").also { holder.actions[48]={openMain(player,0)} })
            if(rows.size==config.itemsPerPage) inv.setItem(53,button(Material.ARROW,"Next Page").also { holder.actions[53]={openYourAuctions(player,page+1)} })
            if(rows.isEmpty()) inv.setItem(22,button(Material.BARRIER,"No active auctions found."))
            player.openInventory(inv)
        }) }
    }

    private fun confirmCancel(player:Player,auction:Auction) {
        val holder=AuctionGuiHolder(player.uniqueId,Screen.CONFIRM,0,null,AuctionSort.NEWEST);val inv=holder.create("Cancel Auction?",27)
        inv.setItem(13,listing(auction,null,"ID: ${auction.auctionId}"))
        inv.setItem(11,button(Material.RED_CONCRETE,"CONFIRM CANCELLATION","The item will move to Expired Items.").also { holder.actions[11]={cancel(player,auction.auctionId);openYourAuctions(player,0)} })
        inv.setItem(15,button(Material.GRAY_CONCRETE,"KEEP AUCTION","Return without cancelling.").also { holder.actions[15]={openYourAuctions(player,0)} })
        player.openInventory(inv)
    }

    private fun openItems(player:Player,page:Int,kind:AuctionItemKind) {
        val load=if(kind==AuctionItemKind.PURCHASED) service.listPurchasedItems(player.uniqueId,page,config.itemsPerPage) else service.listExpiredItems(player.uniqueId,page,config.itemsPerPage)
        load.whenComplete { rows,failure -> Bukkit.getScheduler().runTask(plugin,Runnable {
            if(!player.isOnline)return@Runnable
            if(failure!=null){player.sendMessage(Component.text("Stored items could not be loaded.",NamedTextColor.RED));return@Runnable}
            val holder=AuctionGuiHolder(player.uniqueId,if(kind==AuctionItemKind.PURCHASED)Screen.PURCHASED else Screen.EXPIRED,page,null,AuctionSort.NEWEST)
            val title=if(kind==AuctionItemKind.PURCHASED)"Purchased Items" else "Expired Items";val inv=holder.create(title,54)
            rows.forEachIndexed { i,row ->
                val item=PaperItemStackCodec.decode(row.item.payload)
                if(item!=null) inv.setItem(i,item.clone().also { addLore(it,listOf(Component.text(if(row.kind==AuctionItemKind.PURCHASED)"Click to claim this purchased item." else "Click to reclaim this item."))) })
                holder.actions[i]={claim(player,row,kind)}
            }
            if(rows.isEmpty()) inv.setItem(22,button(Material.BARRIER,"No stored items found."))
            inv.setItem(45,button(if(page>0)Material.ARROW else Material.GRAY_STAINED_GLASS_PANE,"Previous Page").also { if(page>0)holder.actions[45]={openItems(player,page-1,kind)} })
            inv.setItem(49,button(Material.BOOK,"Back","Return to the Auction House.").also { holder.actions[49]={openMain(player,0)} })
            if(rows.size==config.itemsPerPage) inv.setItem(53,button(Material.ARROW,"Next Page").also { holder.actions[53]={openItems(player,page+1,kind)} })
            player.openInventory(inv)
        }) }
    }

    private fun claim(player:Player,row:AuctionItem,viewKind:AuctionItemKind) {
        if(!claiming.add(player.uniqueId)) return
        val existingClaimToken=row.claimToken
        if(existingClaimToken!=null && hasClaimMarker(player,existingClaimToken)) {
            service.completeClaim(existingClaimToken,player.uniqueId).whenComplete { _,failure -> Bukkit.getScheduler().runTask(plugin,Runnable {
                claiming.remove(player.uniqueId)
                if(failure==null) { removeClaimMarker(player,existingClaimToken);openItems(player,0,viewKind) }
                else player.sendMessage(Component.text("Your previous item delivery is still being saved.",NamedTextColor.YELLOW))
            }) }
            return
        }
        val item=PaperItemStackCodec.decode(row.item.payload)
        if(item==null){claiming.remove(player.uniqueId);player.sendMessage(Component.text("Stored item data could not be read; the item remains stored.",NamedTextColor.RED));return}
        if(!canFit(player,item)){claiming.remove(player.uniqueId);player.sendMessage(Component.text("Your inventory is full.",NamedTextColor.RED));return}
        val future=if(viewKind==AuctionItemKind.PURCHASED)service.beginClaimPurchasedItem(row.itemId,player.uniqueId) else service.beginClaimExpiredItem(row.itemId,player.uniqueId)
        future.whenComplete { stored,failure -> Bukkit.getScheduler().runTask(plugin,Runnable {
            if(!player.isOnline){claiming.remove(player.uniqueId);return@Runnable}
            if(failure!=null){claiming.remove(player.uniqueId);player.sendMessage(Component.text(unwrap(failure).message?:"Item claim failed.",NamedTextColor.RED));return@Runnable}
            val restored=PaperItemStackCodec.decode(stored.item.payload)
            if(restored!=null) {
                if(!canFit(player,restored)){claiming.remove(player.uniqueId);player.sendMessage(Component.text("Your inventory is full. The item remains stored.",NamedTextColor.RED));return@Runnable}
                setClaimMarker(restored,stored.claimToken)
                val leftovers=player.inventory.addItem(restored)
                if(leftovers.isNotEmpty()) { claiming.remove(player.uniqueId);plugin.logger.severe("Auction item ${row.itemId} capacity changed during delivery; claim remains recoverable") }
                else service.completeClaim(stored.claimToken,player.uniqueId).whenComplete { _,completeFailure -> Bukkit.getScheduler().runTask(plugin,Runnable {
                    claiming.remove(player.uniqueId)
                    if(completeFailure==null) { removeClaimMarker(player,stored.claimToken);player.sendMessage(Component.text("Item claimed successfully.",NamedTextColor.GREEN));openItems(player,0,viewKind) }
                    else player.sendMessage(Component.text("Item delivered; its claim will be safely reconciled when you reconnect.",NamedTextColor.YELLOW))
                }) }
            }
        }) }
    }

    private fun setClaimMarker(item:ItemStack,token:UUID) { item.itemMeta=item.itemMeta?.also { it.persistentDataContainer.set(claimKey,PersistentDataType.STRING,token.toString()) } }
    private fun hasClaimMarker(player:Player,token:UUID):Boolean=player.inventory.contents.filterNotNull().any { stack->stack.itemMeta?.persistentDataContainer?.get(claimKey,PersistentDataType.STRING)==token.toString() }
    private fun removeClaimMarker(player:Player,token:UUID) { player.inventory.contents.forEachIndexed { slot,stack->if(stack?.itemMeta?.persistentDataContainer?.get(claimKey,PersistentDataType.STRING)==token.toString()) { stack.itemMeta=stack.itemMeta?.also { it.persistentDataContainer.remove(claimKey) };player.inventory.setItem(slot,stack) } } }

    private fun canFit(player:Player,item:ItemStack):Boolean {
        val inventory=Bukkit.createInventory(null,player.inventory.storageContents.size)
        inventory.storageContents=player.inventory.storageContents.map { it?.clone() }.toTypedArray()
        return inventory.addItem(item.clone()).isEmpty()
    }

    private fun startSearch(player:Player,page:Int,sort:AuctionSort) {
        player.closeInventory();val expiry=System.currentTimeMillis()+config.searchTimeoutSeconds*1000
        searching[player.uniqueId]=expiry
        searchTimers.remove(player.uniqueId)?.cancel()
        searchTimers[player.uniqueId]=Bukkit.getScheduler().runTaskLater(plugin,Runnable {
            if(searching.remove(player.uniqueId,expiry)) { searchTimers.remove(player.uniqueId);if(player.isOnline)player.sendMessage(Component.text("Search timed out.",NamedTextColor.YELLOW)) }
        },config.searchTimeoutSeconds*20)
        player.sendMessage(Component.text("Enter a search term, or type \"cancel\" to abort.",NamedTextColor.YELLOW))
    }

    /** Called by ChatPaperModule before ChatService. The matching chat message never reaches network chat. */
    fun consumeChatInput(uuid:UUID,message:String):Boolean {
        val expiry=searching[uuid]?:return false
        if(System.currentTimeMillis()>expiry) { searching.remove(uuid,expiry);searchTimers.remove(uuid)?.cancel();return false }
        if(!searching.remove(uuid,expiry)) return false
        searchTimers.remove(uuid)?.cancel()
        Bukkit.getScheduler().runTask(plugin,Runnable {
            val player=Bukkit.getPlayer(uuid)?:return@Runnable
            if(message.trim().equals("cancel",ignoreCase=true)) { player.sendMessage(Component.text("Search cancelled.",NamedTextColor.YELLOW));openMain(player,0) }
            else { player.sendMessage(Component.text("Searching auctions...",NamedTextColor.YELLOW));openMain(player,0,message.trim().take(128),sortByPlayer[uuid]?:AuctionSort.NEWEST) }
        })
        return true
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    fun onInventoryClick(event:InventoryClickEvent) {
        if(event.whoClicked is Player && (event.whoClicked.uniqueId in claiming || event.whoClicked.uniqueId in lockedSellers)) { event.isCancelled=true;return }
        val holder=event.view.topInventory.holder as? AuctionGuiHolder?:return
        event.isCancelled=true
        if(event.clickedInventory!==event.view.topInventory) return
        if(event.click !in setOf(ClickType.LEFT,ClickType.RIGHT)) return
        holder.actions[event.rawSlot]?.invoke()
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    fun onInventoryDrag(event:InventoryDragEvent) { if(event.whoClicked is Player && (event.whoClicked.uniqueId in claiming || event.whoClicked.uniqueId in lockedSellers) || event.view.topInventory.holder is AuctionGuiHolder) event.isCancelled=true }
    @EventHandler fun onQuit(event:PlayerQuitEvent) {
        val uuid=event.player.uniqueId;searching.remove(uuid);searchTimers.remove(uuid)?.cancel();sortByPlayer.remove(uuid);purchasing.remove(uuid);claiming.remove(uuid);lockedSellers.remove(uuid)
    }
    @EventHandler fun onJoin(event:PlayerJoinEvent) {
        service.listPendingClaims(event.player.uniqueId).whenComplete { claims,failure -> if(failure==null) Bukkit.getScheduler().runTask(plugin,Runnable {
            if(!event.player.isOnline)return@Runnable
            claims.filter { hasClaimMarker(event.player,it.claimToken) }.forEach { claim->
                service.completeClaim(claim.claimToken,event.player.uniqueId).whenComplete { _,completeFailure ->
                    if(completeFailure!=null) plugin.logger.severe("Could not reconcile delivered auction item ${claim.itemId}: ${completeFailure.message}")
                    else Bukkit.getScheduler().runTask(plugin,Runnable { removeClaimMarker(event.player,claim.claimToken) })
                }
            }
        }) }
    }

    private fun listing(auction:Auction,query:String?,extra:String?=null):ItemStack {
        val stack=PaperItemStackCodec.decode(auction.item.payload)?:ItemStack(Material.BARRIER)
        stack.amount=auction.item.payload.let { runCatching { PaperItemStackCodec.decode(it)?.amount }.getOrNull() ?: 1 }.coerceIn(1,stack.maxStackSize)
        val remaining=Duration.between(Instant.now(),auction.expiresAt).coerceAtLeast(Duration.ZERO)
        addLore(stack,buildList {
            add(Component.text("Price: ${auction.price.format()} TitanCoins",NamedTextColor.GOLD))
            add(Component.text("Seller: ${auction.sellerName.take(32)}",NamedTextColor.GRAY))
            add(Component.text("Expires in: ${remaining.toHours()}h ${(remaining.toMinutes()%60)}m",NamedTextColor.GRAY))
            extra?.let { add(Component.text(it,NamedTextColor.DARK_GRAY)) }
            if(query!=null) add(Component.text("Search: $query",NamedTextColor.GRAY))
            add(Component.text("Click to review.",NamedTextColor.YELLOW))
        })
        return stack
    }
    private fun button(material:Material,name:String,vararg lore:String):ItemStack=ItemStack(material).also { stack->stack.itemMeta=stack.itemMeta?.also { meta->meta.displayName(Component.text(name,NamedTextColor.YELLOW));meta.lore(lore.map { Component.text(it,NamedTextColor.GRAY) }) } }
    private fun addLore(stack:ItemStack,lines:List<Component>) { stack.itemMeta=stack.itemMeta?.also { meta->meta.lore((meta.lore()?:emptyList())+lines) } }
    private fun searchText(item:ItemStack):String=buildString {
        append(item.type.name);item.itemMeta?.let { meta->meta.displayName()?.let { append(' ').append(PlainTextComponentSerializer.plainText().serialize(it)) };meta.lore()?.forEach { append(' ').append(PlainTextComponentSerializer.plainText().serialize(it)) } }
    }.lowercase().take(8192)
    private fun showInfo(player:Player) { player.sendMessage(Component.text("Auction House — buy and sell items across the network.",NamedTextColor.GOLD));player.sendMessage(Component.text("/ah · /ah sell <price> · /ah expired · /ah purchased · /ah cancel <id> · /ah info",NamedTextColor.YELLOW)) }
    private fun AuctionSort.label()=when(this){AuctionSort.NEWEST->"Newest First";AuctionSort.OLDEST->"Oldest First";AuctionSort.PRICE_DESC->"Highest Price";AuctionSort.PRICE_ASC->"Lowest Price";AuctionSort.YOUR_AUCTIONS->"Your Auctions"}
    private fun unwrap(failure:Throwable):Throwable { var current=failure;while(current is CompletionException && current.cause!=null)current=current.cause!!;return current }

    private enum class Screen { MAIN, CONFIRM, YOURS, EXPIRED, PURCHASED }
    private class AuctionGuiHolder(val owner:UUID,val screen:Screen,val page:Int,val query:String?,val sort:AuctionSort):InventoryHolder {
        lateinit var guiInventory:Inventory
        val actions=mutableMapOf<Int,()->Unit>()
        fun create(title:String,size:Int):Inventory { guiInventory=Bukkit.createInventory(this,size,Component.text(title));return guiInventory }
        override fun getInventory():Inventory=guiInventory
    }
}
