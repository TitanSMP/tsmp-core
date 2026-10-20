package de.titansmp.auctionhouse

import de.titansmp.api.auctionhouse.*
import de.titansmp.api.economy.EconomyException
import de.titansmp.api.economy.EconomyReason
import de.titansmp.api.economy.EconomyService
import de.titansmp.api.economy.Money
import de.titansmp.api.event.EventBus
import de.titansmp.api.event.EventMetadata
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import java.util.logging.Level
import java.util.logging.Logger

/** Marketplace coordinator. MariaDB owns listings; Redis only publishes small invalidations. */
class DefaultAuctionHouseService(
    private val repository: JdbcAuctionRepository,
    private val economy: EconomyService,
    private val eventBus: EventBus,
    private val executor: Executor,
    private val serverId: String,
    private val clock: Clock = Clock.systemUTC(),
    private val logger: Logger = Logger.getLogger(DefaultAuctionHouseService::class.java.name),
) : AuctionHouseService {
    override fun createAuction(seller: UUID, item: StoredAuctionItem, price: Money, activeLimit: Int, expiresAt: Instant): CompletionStage<Auction> {
        if (item.payload.isEmpty()) return failed(AuctionHouseException(AuctionFailureCode.EMPTY_ITEM,"There is no item to sell."))
        if (!price.isPositive()) return failed(AuctionHouseException(AuctionFailureCode.INVALID_PRICE,"Price must be greater than zero."))
        if (activeLimit < 0) return failed(IllegalArgumentException("Active auction limit cannot be negative"))
        val now=clock.instant()
        val auction=Auction(UUID.randomUUID(),seller,item,price,now,expiresAt,AuctionStatus.ACTIVE)
        return db { repository.create(auction,activeLimit) }.thenCompose { saved->publish(saved);CompletableFuture.completedFuture(saved) }
    }

    override fun getAuction(auctionId: UUID): CompletionStage<Auction?> = db { repository.get(auctionId) }
    override fun storeReturnedItem(owner:UUID,item:StoredAuctionItem):CompletionStage<Unit> = db { repository.storeReturnedItem(owner,item);Unit }
    override fun listAuctions(viewer: UUID, query: String?, sort: AuctionSort, page: Int, pageSize: Int): CompletionStage<AuctionPage> =
        db { repository.list(viewer,query?.takeIf(String::isNotBlank),sort,page,pageSize) }

    override fun purchaseAuction(auctionId: UUID, buyer: UUID, serverId: String): CompletionStage<Auction> =
        db { repository.beginPurchase(auctionId,buyer,clock.instant()) }.thenCompose { pending ->
            economy.transferOnce(auctionId,buyer,pending.sellerUuid,pending.price,EconomyReason.AUCTION_HOUSE,null,serverId)
                .handle { _, failure ->
                    if (failure != null) {
                        val cause=unwrap(failure)
                        if (cause is de.titansmp.core.economy.EconomyInsufficientFundsException ||
                            cause is EconomyException && cause.code==EconomyException.Code.INSUFFICIENT_FUNDS
                        ) {
                            db { repository.resetPurchase(auctionId,buyer) }.thenCompose<Auction> { failed(AuctionHouseException(AuctionFailureCode.INSUFFICIENT_FUNDS,"You do not have enough TitanCoins.")) }
                        } else failed<Auction>(cause)
                    } else {
                        db { repository.finishPurchase(auctionId,buyer,clock.instant()) }.thenApply { sold -> publish(sold);sold }
                    }
                }.thenCompose { it }
        }

    override fun cancelAuction(auctionId: UUID, seller: UUID): CompletionStage<Auction> =
        db { repository.cancel(auctionId,seller,clock.instant()) }.thenApply { publish(it);it }

    override fun listExpiredItems(owner: UUID, page: Int, pageSize: Int): CompletionStage<List<AuctionItem>> =
        db { repository.items(owner,AuctionItemKind.EXPIRED,page,pageSize) }
    override fun listPurchasedItems(owner: UUID, page: Int, pageSize: Int): CompletionStage<List<AuctionItem>> =
        db { repository.items(owner,AuctionItemKind.PURCHASED,page,pageSize) }
    override fun beginClaimExpiredItem(itemId: UUID, owner: UUID): CompletionStage<AuctionItemClaim> = db { repository.beginClaim(owner,itemId) }
    override fun beginClaimPurchasedItem(itemId: UUID, owner: UUID): CompletionStage<AuctionItemClaim> = db { repository.beginClaim(owner,itemId) }
    override fun completeClaim(claimToken:UUID,owner:UUID):CompletionStage<Unit> = db { repository.completeClaim(owner,claimToken) }.thenApply { (itemId,kind) ->
        eventBus.publish(AuctionItemClaimed(metadata(AuctionItemClaimed.TYPE),itemId,owner,kind)).exceptionally { logger.log(Level.WARNING,"Could not publish auction item claim",it);Unit }
        Unit
    }
    override fun listPendingClaims(owner:UUID):CompletionStage<List<AuctionItemClaim>> = db { repository.pendingClaims(owner) }
    override fun listPlayerAuctions(owner: UUID, page: Int, pageSize: Int): CompletionStage<List<Auction>> = db { repository.sellerAuctions(owner,page,pageSize) }

    override fun expireDueAuctions(): CompletionStage<Int> = db { repository.expire(clock.instant()) }.thenApply { expired -> expired.forEach(::publish);expired.size }

    /** Resume durable PURCHASING rows after a crash. Stable transfer IDs make replays exactly once. */
    fun recoverPendingPurchases(): CompletionStage<Int> = db { repository.pendingPurchases() }.thenCompose { pending ->
        CompletableFuture.allOf(*pending.map { auction ->
            recoverPurchase(auction)
                .exceptionally { failure -> logger.log(Level.SEVERE,"Could not recover auction purchase ${auction.auctionId}",unwrap(failure));null }
                .toCompletableFuture()
        }.toTypedArray()).thenApply { pending.size }
    }

    private fun recoverPurchase(auction:Auction):CompletionStage<Unit> {
        val buyer=requireNotNull(auction.buyerUuid)
        return economy.transferOnce(auction.auctionId,buyer,auction.sellerUuid,auction.price,EconomyReason.AUCTION_HOUSE,null,serverId)
            .thenCompose { db { repository.finishPurchase(auction.auctionId,buyer,clock.instant()) }.thenApply { publish(it);Unit } }
            .handle { _,failure ->
                if(failure==null) CompletableFuture.completedFuture(Unit)
                else {
                    val cause=unwrap(failure)
                    if(isInsufficientFunds(cause)) db { repository.resetPurchase(auction.auctionId,buyer);Unit }
                    else failed<Unit>(cause)
                }
            }.thenCompose { it }
    }

    private fun isInsufficientFunds(failure:Throwable)=failure is de.titansmp.core.economy.EconomyInsufficientFundsException ||
        failure is EconomyException && failure.code==EconomyException.Code.INSUFFICIENT_FUNDS

    private fun publish(auction:Auction) {
        eventBus.publish(AuctionChanged(metadata(AuctionChanged.TYPE),auction.auctionId,auction.sellerUuid,auction.buyerUuid,auction.status))
            .exceptionally { logger.log(Level.WARNING,"Could not publish auction invalidation for ${auction.auctionId}",it);Unit }
    }
    private fun metadata(type:de.titansmp.api.event.EventType)=EventMetadata(eventType=type,sourceServerId=serverId)
    private fun <T> db(block:()->T):CompletableFuture<T> = CompletableFuture.supplyAsync(block,executor)
    private fun unwrap(t:Throwable):Throwable { var x=t;while(x is CompletionException && x.cause!=null)x=x.cause!!;return x }
    private fun <T> failed(t:Throwable):CompletableFuture<T> = CompletableFuture<T>().also { it.completeExceptionally(t) }
}
