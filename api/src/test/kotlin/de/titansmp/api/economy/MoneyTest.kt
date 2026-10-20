package de.titansmp.api.economy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MoneyTest {
    @Test
    fun `parses whole and fractional amounts into exact minor units`() {
        assertEquals(0L, Money.of("0").minorUnits)
        assertEquals(100L, Money.of("1").minorUnits)
        assertEquals(1050L, Money.of("10.5").minorUnits)
        assertEquals(1050L, Money.of("10.50").minorUnits)
        assertEquals(1L, Money.of("0.01").minorUnits)
        assertEquals(1_000_001L, Money.of("10000.01").minorUnits)
        assertEquals(92233720368547758L, Money.of("922337203685477.58").minorUnits)
    }

    @Test
    fun `rejects malformed negative and over-precise input without rounding`() {
        listOf("", " ", "abc", "10..0", "10.001", "1,000.00", "+10", ".5", "10.", "-0.01", "-10").forEach { input ->
            assertFailsWith<MoneyFormatException>("Expected '$input' to be rejected") { Money.of(input) }
        }
    }

    @Test
    fun `parsing above the long minor-unit range is an overflow not silent truncation`() {
        assertFailsWith<MoneyOverflowException> { Money.of("92233720368547758.08") }
        assertFailsWith<MoneyOverflowException> { Money.of("999999999999999999") }
    }

    @Test
    fun `formats with fixed grouping and two decimal places`() {
        assertEquals("0.00", Money.ofMinor(0).format())
        assertEquals("0.01", Money.ofMinor(1).format())
        assertEquals("1.00", Money.ofMinor(100).format())
        assertEquals("1,000.00", Money.ofMinor(100_000).format())
        assertEquals("1,250.50", Money.ofMinor(125_050).format())
        assertEquals("1,000,000.50", Money.ofMinor(100_000_050).format())
        assertEquals("-1.00", Money.ofMinor(-100).format())
    }

    @Test
    fun `currency label is central and consistent`() {
        assertEquals("TITANCOINS", TitanCurrency.ID)
        assertEquals("TitanCoins", TitanCurrency.DISPLAY_NAME)
        assertEquals("1,250.50 TitanCoins", TitanCurrency.label(Money.of("1250.50")))
    }

    @Test
    fun `addition and subtraction are exact and checked`() {
        val tenFifty = Money.of("10.50")
        assertEquals(Money.of("11.00"), tenFifty.plus(Money.of("0.50")))
        assertEquals(Money.of("10.00"), tenFifty.minus(Money.of("0.50")))

        assertFailsWith<MoneyOverflowException> { Money.ofMinor(Long.MAX_VALUE).plus(Money.ofMinor(1)) }
        assertFailsWith<MoneyOverflowException> { Money.ofMinor(Long.MIN_VALUE).minus(Money.ofMinor(1)) }
    }

    @Test
    fun `zero comparison and sign helpers behave`() {
        assertTrue(Money.ZERO.isZero())
        assertFalse(Money.ZERO.isPositive())
        assertFalse(Money.ZERO.isNegative())
        assertTrue(Money.ZERO.isNonNegative())
        assertTrue(Money.ofMinor(1).isPositive())
        assertTrue(Money.ofMinor(-1).isNegative())

        assertTrue(Money.of("10.00") > Money.of("9.99"))
        assertTrue(Money.of("10.00") == Money.of("10"))
        assertEquals(0, Money.of("1.00").compareTo(Money.ofMinor(100)))
        assertEquals(Money.of("2.00"), maxOf(Money.of("1.00"), Money.of("2.00")))
    }
}
