package de.titansmp.auctionhouse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AuctionLimitResolverTest {
    @Test fun `configured default applies without grants`() { assertEquals(5,AuctionLimitResolver.resolve(emptyList(),5)) }
    @Test fun `highest valid numeric grant applies`() { assertEquals(25,AuctionLimitResolver.resolve(listOf("titansmp.auction.limit.5","titansmp.auction.limit.25","titansmp.auction.limit.bad"),5)) }
    @Test fun `zero means unlimited`() { assertEquals(0,AuctionLimitResolver.resolve(listOf("titansmp.auction.limit.0","titansmp.auction.limit.10"),5)) }
    @Test fun `unrelated grants are ignored`() { assertEquals(7,AuctionLimitResolver.resolve(listOf("titansmp.auction.limit.7x","other.auction.limit.99"),7)) }
}
