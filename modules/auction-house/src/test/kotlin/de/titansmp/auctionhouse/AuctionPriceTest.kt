package de.titansmp.auctionhouse

import de.titansmp.api.economy.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AuctionPriceTest {
    @Test fun `Money parser accepts exact decimal minor units`() { assertEquals(125_050L,Money.of("1250.50").minorUnits) }
    @Test fun `Money parser rejects fractional rounding and double notation`() {
        assertThrows(IllegalArgumentException::class.java){Money.of("1.001")}
        assertThrows(IllegalArgumentException::class.java){Money.of("NaN")}
        assertThrows(IllegalArgumentException::class.java){Money.of("Infinity")}
        assertThrows(IllegalArgumentException::class.java){Money.of("1e3")}
    }
}
