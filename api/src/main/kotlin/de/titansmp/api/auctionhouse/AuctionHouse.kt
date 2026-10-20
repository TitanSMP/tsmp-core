package de.titansmp.api.auctionhouse

import de.titansmp.api.economy.Money
import de.titansmp.api.event.EventMetadata
import de.titansmp.api.event.EventType
import de.titansmp.api.event.NetworkEvent
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletionStage

/** Complete, platform-neutral serialized item bytes plus text indexed by the platform adapter. */
class StoredAuctionItem(payload: ByteArray, val searchText: String) {
    val payload: ByteArray = payload.copyOf()
    init { require(this.payload.isNotEmpty()); require(searchText.length <= 8192) }
    override fun equals(other: Any?): Boolean = other is StoredAuctionItem && payload.contentEquals(other.payload) && searchText == other.searchText
    override fun hashCode(): Int = 31 * payload.contentHashCode() + searchText.hashCode()
}

enum class AuctionStatus { ACTIVE, PURCHASING, SOLD, EXPIRED, CANCELLED }
enum class AuctionSort { NEWEST, OLDEST, PRICE_DESC, PRICE_ASC, YOUR_AUCTIONS }
enum class AuctionItemKind { EXPIRED, CANCELLED, PURCHASED }

data class Auction(
    val auctionId: UUID, val sellerUuid: UUID, val item: StoredAuctionItem, val price: Money,
    val createdAt: Instant, val expiresAt: Instant, val status: AuctionStatus,
    val buyerUuid: UUID? = null, val purchasedAt: Instant? = null,
    val cancelledAt: Instant? = null, val expiredAt: Instant? = null,
    val sellerName: String = "",
)

data class AuctionItem(val itemId: UUID, val auctionId: UUID, val ownerUuid: UUID, val item: StoredAuctionItem, val kind: AuctionItemKind, val createdAt: Instant, val claimToken: UUID? = null)
data class AuctionItemClaim(val itemId: UUID, val ownerUuid: UUID, val item: StoredAuctionItem, val kind: AuctionItemKind, val claimToken: UUID)
data class AuctionPage(val items: List<Auction>, val totalItems: Long, val page: Int, val pageSize: Int) {
    val totalPages: Int get() = maxOf(1, ((totalItems + pageSize - 1) / pageSize).toInt())
}

enum class AuctionFailureCode { INVALID_PRICE, EMPTY_ITEM, LIMIT_REACHED, NOT_FOUND, NOT_ACTIVE, EXPIRED, OWN_AUCTION, INSUFFICIENT_FUNDS, NOT_OWNER, CONFLICT, ITEM_NOT_FOUND, INVENTORY_FULL, DATABASE }
class AuctionHouseException(val code: AuctionFailureCode, message: String, cause: Throwable? = null) : RuntimeException(message, cause)

interface AuctionHouseService {
    fun createAuction(seller: UUID, item: StoredAuctionItem, price: Money, activeLimit: Int, expiresAt: Instant): CompletionStage<Auction>
    /** Persists an item that had to be rolled back after a failed listing attempt. */
    fun storeReturnedItem(owner: UUID, item: StoredAuctionItem): CompletionStage<Unit>
    fun getAuction(auctionId: UUID): CompletionStage<Auction?>
    fun listAuctions(viewer: UUID, query: String?, sort: AuctionSort, page: Int, pageSize: Int): CompletionStage<AuctionPage>
    fun purchaseAuction(auctionId: UUID, buyer: UUID, serverId: String): CompletionStage<Auction>
    fun cancelAuction(auctionId: UUID, seller: UUID): CompletionStage<Auction>
    fun listExpiredItems(owner: UUID, page: Int, pageSize: Int): CompletionStage<List<AuctionItem>>
    fun beginClaimExpiredItem(itemId: UUID, owner: UUID): CompletionStage<AuctionItemClaim>
    fun listPurchasedItems(owner: UUID, page: Int, pageSize: Int): CompletionStage<List<AuctionItem>>
    fun beginClaimPurchasedItem(itemId: UUID, owner: UUID): CompletionStage<AuctionItemClaim>
    fun completeClaim(claimToken: UUID, owner: UUID): CompletionStage<Unit>
    fun listPendingClaims(owner: UUID): CompletionStage<List<AuctionItemClaim>>
    fun listPlayerAuctions(owner: UUID, page: Int, pageSize: Int): CompletionStage<List<Auction>>
    fun expireDueAuctions(): CompletionStage<Int>
}

data class AuctionChanged(override val metadata: EventMetadata, val auctionId: UUID, val sellerUuid: UUID, val buyerUuid: UUID?, val status: AuctionStatus) : NetworkEvent {
    companion object { val TYPE = EventType("auction.changed"); const val VERSION = 1 }
}
data class AuctionItemClaimed(override val metadata: EventMetadata, val itemId: UUID, val ownerUuid: UUID, val kind: AuctionItemKind) : NetworkEvent {
    companion object { val TYPE = EventType("auction.item_claimed"); const val VERSION = 1 }
}
