package de.titansmp.auctionhouse

import de.titansmp.api.auctionhouse.*
import de.titansmp.api.economy.Money
import java.nio.ByteBuffer
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/** All marketplace state changes use the shared MariaDB DataSource and row locks. */
class JdbcAuctionRepository(private val dataSource: DataSource) {
    fun create(auction: Auction, limit: Int) = transaction { c ->
        require(limit >= 0)
        c.prepareStatement("SELECT uuid FROM players WHERE uuid=? FOR UPDATE").use { s ->
            s.setBytes(1, auction.sellerUuid.bytes()); s.executeQuery().use { r -> if (!r.next()) throw AuctionHouseException(AuctionFailureCode.DATABASE,"Player identity was not found") }
        }
        c.prepareStatement("SELECT COUNT(*) FROM auction_house_listings WHERE seller_uuid=? AND status IN ('ACTIVE','PURCHASING')").use { s ->
            s.setBytes(1, auction.sellerUuid.bytes()); s.executeQuery().use { r -> r.next(); if (limit != 0 && r.getLong(1) >= limit) throw AuctionHouseException(AuctionFailureCode.LIMIT_REACHED, "Your active auction limit has been reached.") }
        }
        c.prepareStatement("INSERT INTO auction_house_listings (id,seller_uuid,item_data,search_text,price_minor,currency,status,created_at,expires_at) VALUES (?,?,?,?,?,'TitanCoins','ACTIVE',?,?)").use { s ->
            s.setBytes(1, auction.auctionId.bytes()); s.setBytes(2, auction.sellerUuid.bytes()); s.setBytes(3, auction.item.payload)
            s.setString(4, auction.item.searchText); s.setLong(5, auction.price.minorUnits); s.utc(6, auction.createdAt); s.utc(7, auction.expiresAt); s.executeUpdate()
        }
        auction
    }

    fun storeReturnedItem(owner:UUID,item:StoredAuctionItem):Unit=transaction { c ->
        c.prepareStatement("SELECT uuid FROM players WHERE uuid=? FOR UPDATE").use { s->s.setBytes(1,owner.bytes());s.executeQuery().use { r->if(!r.next())throw AuctionHouseException(AuctionFailureCode.DATABASE,"Player identity was not found") } }
        val now=Instant.now();val id=UUID.randomUUID()
        c.prepareStatement("INSERT INTO auction_house_listings (id,seller_uuid,item_data,search_text,price_minor,currency,status,created_at,expires_at,cancelled_at) VALUES (?,?,?,?,1,'TitanCoins','CANCELLED',?,?,?)").use { s->s.setBytes(1,id.bytes());s.setBytes(2,owner.bytes());s.setBytes(3,item.payload);s.setString(4,item.searchText);s.utc(5,now);s.utc(6,now);s.utc(7,now);check(s.executeUpdate()==1) }
    }

    fun get(id: UUID): Auction? = dataSource.connection.use { c ->
        c.prepareStatement("SELECT a.*, p.username seller_name FROM auction_house_listings a LEFT JOIN players p ON p.uuid=a.seller_uuid WHERE a.id=?").use { s ->
            s.setBytes(1,id.bytes()); s.executeQuery().use { r -> if (r.next()) r.auction() else null }
        }
    }

    fun list(viewer: UUID, query: String?, sort: AuctionSort, page: Int, size: Int): AuctionPage = dataSource.connection.use { c ->
        require(page in 0..1_000_000 && size in 1..45)
        val keyword=query?.trim()?.lowercase()?.replace("%","")?.replace("_","")?.take(128)?.takeIf(String::isNotBlank)
        val onlyMine = sort == AuctionSort.YOUR_AUCTIONS
        val filter = buildString { append("status='ACTIVE'"); if (onlyMine) append(" AND seller_uuid=?"); if (keyword!=null) append(" AND LOWER(search_text) LIKE ?") }
        val count = c.prepareStatement("SELECT COUNT(*) FROM auction_house_listings WHERE $filter").use { s ->
            var i=1; if (onlyMine) s.setBytes(i++,viewer.bytes()); if (keyword!=null) s.setString(i,"%$keyword%")
            s.executeQuery().use { r -> r.next(); r.getLong(1) }
        }
        val order = when (sort) { AuctionSort.OLDEST -> "created_at ASC,id ASC"; AuctionSort.PRICE_DESC -> "price_minor DESC,created_at DESC"; AuctionSort.PRICE_ASC -> "price_minor ASC,created_at DESC"; else -> "created_at DESC,id DESC" }
        val offset = Math.multiplyExact(page, size)
        val rows = c.prepareStatement("SELECT a.*,p.username seller_name FROM auction_house_listings a LEFT JOIN players p ON p.uuid=a.seller_uuid WHERE $filter ORDER BY a.$order LIMIT ? OFFSET ?".replace("a.status", "a.status").replace("status=", "a.status=" )).use { s ->
            var i=1; if (onlyMine) s.setBytes(i++,viewer.bytes()); if (keyword!=null) s.setString(i++,"%$keyword%"); s.setInt(i++,size); s.setInt(i,offset)
            s.executeQuery().use { r -> buildList { while(r.next()) add(r.auction()) } }
        }
        AuctionPage(rows,count,page,size)
    }

    fun beginPurchase(id: UUID, buyer: UUID, now: Instant): Auction = transaction { c ->
        val auction = lockAuction(c,id) ?: throw AuctionHouseException(AuctionFailureCode.NOT_FOUND,"This auction is no longer available.")
        if (auction.sellerUuid == buyer) throw AuctionHouseException(AuctionFailureCode.OWN_AUCTION,"You cannot buy your own auction.")
        if (auction.status != AuctionStatus.ACTIVE) throw AuctionHouseException(AuctionFailureCode.NOT_ACTIVE,"This auction is no longer available.")
        if (!now.isBefore(auction.expiresAt)) {
            c.prepareStatement("UPDATE auction_house_listings SET status='EXPIRED',expired_at=? WHERE id=? AND status='ACTIVE'").use { s -> s.utc(1,now);s.setBytes(2,id.bytes());s.executeUpdate() }
            throw AuctionHouseException(AuctionFailureCode.EXPIRED,"This auction has expired.")
        }
        c.prepareStatement("UPDATE auction_house_listings SET status='PURCHASING',buyer_uuid=?,purchase_transaction_id=? WHERE id=? AND status='ACTIVE'").use { s ->
            s.setBytes(1,buyer.bytes());s.setBytes(2,id.bytes());s.setBytes(3,id.bytes());check(s.executeUpdate()==1)
        }
        auction.copy(status=AuctionStatus.PURCHASING,buyerUuid=buyer)
    }

    fun resetPurchase(id: UUID, buyer: UUID) = transaction { c ->
        c.prepareStatement("UPDATE auction_house_listings SET status='ACTIVE',buyer_uuid=NULL,purchase_transaction_id=NULL WHERE id=? AND buyer_uuid=? AND status='PURCHASING'").use { s -> s.setBytes(1,id.bytes());s.setBytes(2,buyer.bytes());s.executeUpdate() }
    }

    fun finishPurchase(id: UUID, buyer: UUID, now: Instant): Auction = transaction { c ->
        val a=lockAuction(c,id) ?: throw AuctionHouseException(AuctionFailureCode.NOT_FOUND,"Auction not found")
        if(a.status==AuctionStatus.SOLD && a.buyerUuid==buyer) return@transaction a
        if(a.status!=AuctionStatus.PURCHASING || a.buyerUuid!=buyer) throw AuctionHouseException(AuctionFailureCode.CONFLICT,"Auction purchase is no longer valid")
        c.prepareStatement("UPDATE auction_house_listings SET status='SOLD',purchased_at=? WHERE id=? AND status='PURCHASING' AND buyer_uuid=?").use { s -> s.utc(1,now);s.setBytes(2,id.bytes());s.setBytes(3,buyer.bytes());check(s.executeUpdate()==1) }
        c.prepareStatement("INSERT INTO auction_house_items (id,auction_id,owner_uuid,item_data,search_text,kind,created_at) VALUES (?,?,?,?,?,'PURCHASED',?) ON DUPLICATE KEY UPDATE id=id").use { s -> s.setBytes(1,UUID.nameUUIDFromBytes("purchase:$id".toByteArray()).bytes());s.setBytes(2,id.bytes());s.setBytes(3,buyer.bytes());s.setBytes(4,a.item.payload);s.setString(5,a.item.searchText);s.utc(6,now);s.executeUpdate() }
        a.copy(status=AuctionStatus.SOLD,buyerUuid=buyer,purchasedAt=now)
    }

    fun cancel(id: UUID, seller: UUID, now: Instant): Auction = transaction { c ->
        val a=lockAuction(c,id) ?: throw AuctionHouseException(AuctionFailureCode.NOT_FOUND,"Auction not found")
        if(a.sellerUuid!=seller) throw AuctionHouseException(AuctionFailureCode.NOT_OWNER,"You can cancel only your own auctions.")
        if(a.status!=AuctionStatus.ACTIVE) throw AuctionHouseException(AuctionFailureCode.NOT_ACTIVE,"Only active auctions can be cancelled.")
        if(!now.isBefore(a.expiresAt)) {
            c.prepareStatement("UPDATE auction_house_listings SET status='EXPIRED',expired_at=? WHERE id=? AND status='ACTIVE'").use { s->s.utc(1,now);s.setBytes(2,id.bytes());s.executeUpdate() }
            throw AuctionHouseException(AuctionFailureCode.EXPIRED,"This auction has expired.")
        }
        c.prepareStatement("UPDATE auction_house_listings SET status='CANCELLED',cancelled_at=? WHERE id=? AND status='ACTIVE'").use { s->s.utc(1,now);s.setBytes(2,id.bytes());check(s.executeUpdate()==1) }
        a.copy(status=AuctionStatus.CANCELLED,cancelledAt=now)
    }

    fun expire(now: Instant, limit: Int=200): List<Auction> = transaction { c ->
        val due=c.prepareStatement("SELECT id FROM auction_house_listings WHERE status='ACTIVE' AND expires_at<=? ORDER BY expires_at LIMIT ? FOR UPDATE").use { s -> s.utc(1,now);s.setInt(2,limit);s.executeQuery().use { r -> buildList { while(r.next()) add(r.getBytes(1).toUuid()) } } }
        due.mapNotNull { id ->
            val a=lockAuction(c,id) ?: return@mapNotNull null
            if(a.status!=AuctionStatus.ACTIVE || a.expiresAt.isAfter(now)) return@mapNotNull null
            c.prepareStatement("UPDATE auction_house_listings SET status='EXPIRED',expired_at=? WHERE id=? AND status='ACTIVE'").use { s->s.utc(1,now);s.setBytes(2,id.bytes());if(s.executeUpdate()==1) a.copy(status=AuctionStatus.EXPIRED,expiredAt=now) else null }
        }
    }

    fun sellerAuctions(owner: UUID,page:Int,size:Int):List<Auction> = dataSource.connection.use { c->
        require(page in 0..1_000_000 && size in 1..45)
        c.prepareStatement("SELECT a.*,p.username seller_name FROM auction_house_listings a LEFT JOIN players p ON p.uuid=a.seller_uuid WHERE a.seller_uuid=? AND a.status='ACTIVE' ORDER BY a.created_at DESC LIMIT ? OFFSET ?").use { s->s.setBytes(1,owner.bytes());s.setInt(2,size);s.setInt(3,page*size);s.executeQuery().use { r->buildList { while(r.next()) add(r.auction()) } } }
    }

    fun items(owner:UUID,kind:AuctionItemKind,page:Int,size:Int):List<AuctionItem> = dataSource.connection.use { c->
        require(page in 0..1_000_000 && size in 1..45)
        if(kind==AuctionItemKind.EXPIRED || kind==AuctionItemKind.CANCELLED) c.prepareStatement("SELECT id, seller_uuid owner_uuid,item_data,search_text,status,created_at,item_claim_token FROM auction_house_listings WHERE seller_uuid=? AND status IN ('EXPIRED','CANCELLED') AND item_claimed_at IS NULL ORDER BY created_at DESC LIMIT ? OFFSET ?").use { s->
            s.setBytes(1,owner.bytes());s.setInt(2,size);s.setInt(3,page*size);s.executeQuery().use { r->buildList { while(r.next()) { val status=r.getString("status");add(AuctionItem(r.getBytes("id").toUuid(),r.getBytes("id").toUuid(),owner,StoredAuctionItem(r.getBytes("item_data"),r.getString("search_text")),if(status=="EXPIRED") AuctionItemKind.EXPIRED else AuctionItemKind.CANCELLED,r.instant("created_at"),r.getBytes("item_claim_token")?.toUuid())) } } }
        } else c.prepareStatement("SELECT * FROM auction_house_items WHERE owner_uuid=? AND kind='PURCHASED' AND claimed_at IS NULL ORDER BY created_at DESC LIMIT ? OFFSET ?").use { s->
            s.setBytes(1,owner.bytes());s.setInt(2,size);s.setInt(3,page*size);s.executeQuery().use { r->buildList { while(r.next()) add(AuctionItem(r.getBytes("id").toUuid(),r.getBytes("auction_id").toUuid(),owner,StoredAuctionItem(r.getBytes("item_data"),r.getString("search_text")),AuctionItemKind.PURCHASED,r.instant("created_at"),r.getBytes("claim_token")?.toUuid())) } }
        }
    }

    fun beginClaim(owner:UUID,itemId:UUID):AuctionItemClaim = transaction { c ->
        val listing=c.prepareStatement("SELECT seller_uuid,item_data,search_text,status,item_claimed_at,item_claim_token FROM auction_house_listings WHERE id=? FOR UPDATE").use { s->s.setBytes(1,itemId.bytes());s.executeQuery().use { r->if(r.next() && r.getBytes("seller_uuid").toUuid()==owner && r.getString("status") in listOf("EXPIRED","CANCELLED") && r.getTimestamp("item_claimed_at")==null) {
            val status=r.getString("status");val bytes=r.getBytes("item_data");val text=r.getString("search_text");val existing=r.getBytes("item_claim_token")?.toUuid()
            Triple(StoredAuctionItem(bytes,text),if(status=="EXPIRED")AuctionItemKind.EXPIRED else AuctionItemKind.CANCELLED,existing)
        } else null } }
        if(listing!=null) {
            val token=listing.third?:UUID.randomUUID().also { value->c.prepareStatement("UPDATE auction_house_listings SET item_claim_token=? WHERE id=? AND item_claimed_at IS NULL").use { s->s.setBytes(1,value.bytes());s.setBytes(2,itemId.bytes());check(s.executeUpdate()==1) } }
            return@transaction AuctionItemClaim(itemId,owner,listing.first,listing.second,token)
        }
        val storedRow=c.prepareStatement("SELECT item_data,search_text,kind,claim_token FROM auction_house_items WHERE id=? AND owner_uuid=? AND claimed_at IS NULL FOR UPDATE").use { s->s.setBytes(1,itemId.bytes());s.setBytes(2,owner.bytes());s.executeQuery().use { r->if(r.next()) Triple(StoredAuctionItem(r.getBytes("item_data"),r.getString("search_text")),AuctionItemKind.valueOf(r.getString("kind")),r.getBytes("claim_token")?.toUuid()) else null } }
            ?: throw AuctionHouseException(AuctionFailureCode.ITEM_NOT_FOUND,"That stored item is no longer available.")
        val token=storedRow.third?:UUID.randomUUID().also { value->c.prepareStatement("UPDATE auction_house_items SET claim_token=? WHERE id=? AND claimed_at IS NULL").use { u->u.setBytes(1,value.bytes());u.setBytes(2,itemId.bytes());check(u.executeUpdate()==1) } }
        AuctionItemClaim(itemId,owner,storedRow.first,storedRow.second,token)
    }

    fun pendingClaims(owner:UUID):List<AuctionItemClaim> = dataSource.connection.use { c -> buildList {
        c.prepareStatement("SELECT id,item_data,search_text,status,item_claim_token FROM auction_house_listings WHERE seller_uuid=? AND status IN ('EXPIRED','CANCELLED') AND item_claimed_at IS NULL AND item_claim_token IS NOT NULL").use { s->s.setBytes(1,owner.bytes());s.executeQuery().use { r->while(r.next()) add(AuctionItemClaim(r.getBytes("id").toUuid(),owner,StoredAuctionItem(r.getBytes("item_data"),r.getString("search_text")),if(r.getString("status")=="EXPIRED")AuctionItemKind.EXPIRED else AuctionItemKind.CANCELLED,r.getBytes("item_claim_token").toUuid())) } }
        c.prepareStatement("SELECT id,auction_id,item_data,search_text,kind,claim_token FROM auction_house_items WHERE owner_uuid=? AND claimed_at IS NULL AND claim_token IS NOT NULL").use { s->s.setBytes(1,owner.bytes());s.executeQuery().use { r->while(r.next()) add(AuctionItemClaim(r.getBytes("id").toUuid(),owner,StoredAuctionItem(r.getBytes("item_data"),r.getString("search_text")),AuctionItemKind.valueOf(r.getString("kind")),r.getBytes("claim_token").toUuid())) } }
    } }

    fun completeClaim(owner:UUID,token:UUID):Pair<UUID,AuctionItemKind> = transaction { c ->
        val listing=c.prepareStatement("SELECT id,status,item_claimed_at FROM auction_house_listings WHERE seller_uuid=? AND item_claim_token=? FOR UPDATE").use { s->s.setBytes(1,owner.bytes());s.setBytes(2,token.bytes());s.executeQuery().use { r->if(r.next()) Triple(r.getBytes("id").toUuid(),r.getString("status"),r.getTimestamp("item_claimed_at")!=null) else null } }
        if(listing!=null) {
            val kind=if(listing.second=="EXPIRED")AuctionItemKind.EXPIRED else AuctionItemKind.CANCELLED
            if(!listing.third) c.prepareStatement("UPDATE auction_house_listings SET item_claimed_at=UTC_TIMESTAMP(3) WHERE id=? AND item_claim_token=? AND item_claimed_at IS NULL").use { s->s.setBytes(1,listing.first.bytes());s.setBytes(2,token.bytes());check(s.executeUpdate()==1) }
            return@transaction listing.first to kind
        }
        val stored=c.prepareStatement("SELECT id,kind,claimed_at FROM auction_house_items WHERE owner_uuid=? AND claim_token=? FOR UPDATE").use { s->s.setBytes(1,owner.bytes());s.setBytes(2,token.bytes());s.executeQuery().use { r->if(r.next()) Triple(r.getBytes("id").toUuid(),AuctionItemKind.valueOf(r.getString("kind")),r.getTimestamp("claimed_at")!=null) else null } } ?: throw AuctionHouseException(AuctionFailureCode.ITEM_NOT_FOUND,"That item claim is no longer available.")
        if(!stored.third) c.prepareStatement("UPDATE auction_house_items SET claimed_at=UTC_TIMESTAMP(3) WHERE owner_uuid=? AND claim_token=? AND claimed_at IS NULL").use { s->s.setBytes(1,owner.bytes());s.setBytes(2,token.bytes());check(s.executeUpdate()==1) }
        stored.first to stored.second
    }

    fun pendingPurchases():List<Auction> = dataSource.connection.use { c->c.prepareStatement("SELECT a.*,p.username seller_name FROM auction_house_listings a LEFT JOIN players p ON p.uuid=a.seller_uuid WHERE a.status='PURCHASING'").use { s->s.executeQuery().use { r->buildList { while(r.next()) add(r.auction()) } } } }

    private fun lockAuction(c:Connection,id:UUID):Auction?=c.prepareStatement("SELECT a.*,p.username seller_name FROM auction_house_listings a LEFT JOIN players p ON p.uuid=a.seller_uuid WHERE a.id=? FOR UPDATE").use { s->s.setBytes(1,id.bytes());s.executeQuery().use { r->if(r.next()) r.auction() else null } }
    private fun ResultSet.auction()=Auction(getBytes("id").toUuid(),getBytes("seller_uuid").toUuid(),StoredAuctionItem(getBytes("item_data"),getString("search_text")),Money.ofMinor(getLong("price_minor")),instant("created_at"),instant("expires_at"),AuctionStatus.valueOf(getString("status")),getBytes("buyer_uuid")?.toUuid(),nullableInstant("purchased_at"),nullableInstant("cancelled_at"),nullableInstant("expired_at"),getString("seller_name")?:"Unknown")
    private fun ResultSet.instant(column:String)=getTimestamp(column).toInstant()
    private fun ResultSet.nullableInstant(column:String)=getTimestamp(column)?.toInstant()
    private fun java.sql.PreparedStatement.utc(index:Int,at:Instant)=setObject(index,LocalDateTime.ofInstant(at,ZoneOffset.UTC))
    private fun UUID.bytes()=ByteBuffer.allocate(16).putLong(mostSignificantBits).putLong(leastSignificantBits).array()
    private fun ByteArray.toUuid()=ByteBuffer.wrap(this).let { UUID(it.long,it.long) }
    private fun <T> transaction(block:(Connection)->T):T=dataSource.connection.use { c->val auto=c.autoCommit;c.autoCommit=false;try{block(c).also{c.commit()}}catch(t:Throwable){try{c.rollback()}catch(r:Throwable){t.addSuppressed(r)};throw t}finally{c.autoCommit=auto} }
}
