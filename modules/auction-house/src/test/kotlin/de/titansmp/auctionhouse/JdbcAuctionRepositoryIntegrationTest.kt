package de.titansmp.auctionhouse

import de.titansmp.api.auctionhouse.*
import de.titansmp.api.economy.Money
import de.titansmp.api.economy.EconomyReason
import de.titansmp.core.economy.EconomyChangeRequest
import de.titansmp.core.economy.EconomyOperation
import de.titansmp.core.economy.EconomyTransferRequest
import de.titansmp.core.economy.JdbcEconomyRepository
import de.titansmp.core.database.DatabaseMigrator
import de.titansmp.core.database.DatabaseService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mariadb.MariaDBContainer
import java.nio.ByteBuffer
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcAuctionRepositoryIntegrationTest {
    @Container private val maria=MariaDBContainer("mariadb:11.4")
    private lateinit var ds:org.mariadb.jdbc.MariaDbDataSource
    private lateinit var repo:JdbcAuctionRepository
    private val now=Instant.parse("2026-10-21T10:00:00Z")

    @BeforeAll fun migrate() {
        ds=org.mariadb.jdbc.MariaDbDataSource().apply { setUrl(maria.jdbcUrl);setUser(maria.username);setPassword(maria.password) }
        DatabaseMigrator().migrate(object:DatabaseService { override val dataSource=ds;override fun start()=Unit;override fun close()=Unit })
        repo=JdbcAuctionRepository(ds)
    }
    @BeforeEach fun clear() { ds.connection.use { c->c.createStatement().use { s->s.executeUpdate("DELETE FROM auction_house_items");s.executeUpdate("DELETE FROM auction_house_listings");s.executeUpdate("DELETE FROM economy_transactions");s.executeUpdate("DELETE FROM economy_balances");s.executeUpdate("DELETE FROM players") } } }

    @Test fun `auction visibility search sorting and pagination are database backed`() {
        val seller=player("Seller");val buyer=player("Buyer")
        val older=create(seller,"diamond sword",300,created=now.minusSeconds(30))
        val newer=create(seller,"diamond pickaxe",100,created=now.minusSeconds(10))
        create(buyer,"emerald",500)
        assertEquals(3,repo.list(buyer,null,AuctionSort.NEWEST,0,2).totalItems)
        assertEquals(2,repo.list(buyer,null,AuctionSort.NEWEST,0,2).items.size)
        assertEquals(2,repo.list(buyer,"DIAMOND",AuctionSort.PRICE_ASC,0,45).items.size)
        assertEquals(100,repo.list(buyer,null,AuctionSort.PRICE_ASC,0,45).items.first().price.minorUnits)
        assertEquals(newer.auctionId,repo.list(buyer,null,AuctionSort.NEWEST,0,45).items.first().auctionId)
        assertEquals(older.auctionId,repo.list(buyer,null,AuctionSort.OLDEST,0,45).items.first().auctionId)
        assertEquals(older.auctionId,repo.list(buyer,null,AuctionSort.PRICE_DESC,0,45).items.first().auctionId)
        assertEquals(3,repo.list(buyer,null,AuctionSort.NEWEST,0,1).totalPages)
        assertEquals(2,repo.list(seller,null,AuctionSort.YOUR_AUCTIONS,0,45).items.size)
    }

    @Test fun `concurrent listing creation respects the active limit`() {
        val seller=player("LimitedSeller");val pool=Executors.newFixedThreadPool(2)
        try {
            val attempts=listOf("emerald","diamond","iron" ).map { text->pool.submit<Boolean>{runCatching{repo.create(Auction(UUID.randomUUID(),seller,StoredAuctionItem(byteArrayOf(1),text),Money.ofMinor(100),now,now.plusSeconds(3600),AuctionStatus.ACTIVE),1);true}.getOrDefault(false)} }
            assertEquals(1,attempts.count{it.get(10,TimeUnit.SECONDS)})
            assertEquals(1,repo.sellerAuctions(seller,0,45).size)
        } finally { pool.shutdownNow() }
    }

    @Test fun `simultaneous purchases allow exactly one buyer`() {
        val seller=player("Seller");val one=player("BuyerOne");val two=player("BuyerTwo");val id=create(seller,"emerald",250).auctionId
        val pool=Executors.newFixedThreadPool(2)
        try {
            val results=listOf(one,two).map { buyer->pool.submit<Boolean> { runCatching { repo.beginPurchase(id,buyer,now);true }.getOrDefault(false) } }
            assertEquals(1,results.count { it.get(10,TimeUnit.SECONDS) })
            assertEquals(AuctionStatus.PURCHASING,repo.get(id)!!.status)
        } finally { pool.shutdownNow() }
    }

    @Test fun `expiration wins at boundary and item remains claimable`() {
        val seller=player("Seller");val buyer=player("Buyer");val auction=create(seller,"emerald",250,now)
        assertThrows(AuctionHouseException::class.java) { repo.beginPurchase(auction.auctionId,buyer,now) }
        val expired=repo.expire(now)
        assertEquals(1,expired.size)
        assertEquals(AuctionStatus.EXPIRED,repo.get(auction.auctionId)!!.status)
        assertEquals(AuctionItemKind.EXPIRED,repo.items(seller,AuctionItemKind.EXPIRED,0,45).single().kind)
        val claim=repo.beginClaim(seller,auction.auctionId)
        assertArrayEquals(auction.item.payload,claim.item.payload)
        assertEquals(claim.claimToken,repo.beginClaim(seller,auction.auctionId).claimToken)
        repo.completeClaim(seller,claim.claimToken)
        assertThrows(AuctionHouseException::class.java) { repo.beginClaim(seller,auction.auctionId) }
    }

    @Test fun `cancellation and purchase have one terminal winner`() {
        val seller=player("Seller");val buyer=player("Buyer");val auction=create(seller,"emerald",250)
        val pool=Executors.newFixedThreadPool(2)
        try {
            val cancel=pool.submit<Boolean>{runCatching{repo.cancel(auction.auctionId,seller,now);true}.getOrDefault(false)}
            val buy=pool.submit<Boolean>{runCatching{repo.beginPurchase(auction.auctionId,buyer,now);true}.getOrDefault(false)}
            assertEquals(1,listOf(cancel,buy).count{it.get(10,TimeUnit.SECONDS)})
            assertTrue(repo.get(auction.auctionId)!!.status in setOf(AuctionStatus.CANCELLED,AuctionStatus.PURCHASING))
        } finally { pool.shutdownNow() }
    }

    @Test fun `purchase completion stores item persistently for offline buyer`() {
        val seller=player("Seller");val buyer=player("Buyer");val auction=create(seller,"enchanted diamond sword",1250)
        repo.beginPurchase(auction.auctionId,buyer,now)
        repo.finishPurchase(auction.auctionId,buyer,now)
        val stored=repo.items(buyer,AuctionItemKind.PURCHASED,0,45).single()
        val claim=repo.beginClaim(buyer,stored.itemId)
        assertArrayEquals(auction.item.payload,claim.item.payload)
        repo.completeClaim(buyer,claim.claimToken)
        assertEquals(AuctionStatus.SOLD,repo.get(auction.auctionId)!!.status)
    }

    @Test fun `stable auction economy transfer ID is idempotent`() {
        val buyer=player("Buyer");val seller=player("OfflineSeller");val economy=JdbcEconomyRepository(ds)
        economy.applyChange(EconomyChangeRequest(UUID.randomUUID(),buyer,EconomyOperation.DEPOSIT,Money.ofMinor(50_000),EconomyReason.ADMIN_GIVE,null,now,"test"))
        val transfer=EconomyTransferRequest(UUID.randomUUID(),buyer,seller,Money.ofMinor(12_500),EconomyReason.AUCTION_HOUSE,null,now,"backend-a")
        val first=economy.applyTransfer(transfer);val replay=economy.applyTransfer(transfer)
        assertEquals(first.sender.newBalance,replay.sender.newBalance)
        assertEquals(Money.ofMinor(37_500),economy.findBalance(buyer))
        assertEquals(Money.ofMinor(12_500),economy.findBalance(seller))
    }

    private fun create(owner:UUID,text:String,price:Long,expires:Instant=now.plusSeconds(3600),created:Instant=now)=
        repo.create(Auction(UUID.randomUUID(),owner,StoredAuctionItem(byteArrayOf(1,2,3),text),Money.ofMinor(price),created,expires,AuctionStatus.ACTIVE),5)
    private fun player(name:String):UUID { val id=UUID.randomUUID();ds.connection.use { c->c.prepareStatement("INSERT INTO players(uuid,username,first_join,last_seen,online) VALUES(?,?,?,?,FALSE)").use { s->s.setBytes(1,id.bytes());s.setString(2,name);s.setTimestamp(3,Timestamp.from(now));s.setTimestamp(4,Timestamp.from(now));s.executeUpdate() } };return id }
    private fun UUID.bytes()=ByteBuffer.allocate(16).putLong(mostSignificantBits).putLong(leastSignificantBits).array()
}
