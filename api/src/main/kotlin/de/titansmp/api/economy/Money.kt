package de.titansmp.api.economy

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Exact monetary value stored as a whole number of minor units.
 *
 * TitanSMP uses one currency with two decimal places, so `100` minor units are `1.00 TitanCoins`.
 * Floating-point values are never used for money.
 *
 * Formatting is deliberately locale-independent: grouping uses `,`, the decimal separator uses `.`,
 * and exactly two fraction digits are always rendered (for example `1,250.50`). This keeps command
 * output, logs, and audit queries identical on every server regardless of the host locale.
 */
@JvmInline
value class Money private constructor(val minorUnits: Long) : Comparable<Money> {
    fun plus(other: Money): Money = try {
        Money(Math.addExact(minorUnits, other.minorUnits))
    } catch (failure: ArithmeticException) {
        throw MoneyOverflowException("The resulting amount is outside the supported money range", failure)
    }

    fun minus(other: Money): Money = try {
        Money(Math.subtractExact(minorUnits, other.minorUnits))
    } catch (failure: ArithmeticException) {
        throw MoneyOverflowException("The resulting amount is outside the supported money range", failure)
    }

    fun isZero(): Boolean = minorUnits == 0L
    fun isPositive(): Boolean = minorUnits > 0L
    fun isNegative(): Boolean = minorUnits < 0L
    fun isNonNegative(): Boolean = minorUnits >= 0L

    /** Canonical fixed-point representation with comma grouping and two decimal places. */
    fun format(): String = formatMinorUnits(minorUnits)

    override fun compareTo(other: Money): Int = minorUnits.compareTo(other.minorUnits)
    override fun toString(): String = format()

    companion object {
        val ZERO: Money = Money(0L)

        /** Wraps an already exact minor-unit amount. Arithmetic callers own overflow validation. */
        fun ofMinor(minorUnits: Long): Money = Money(minorUnits)

        /**
         * Parses user input such as `10`, `10.5`, `10.50`, `0.01`, or `1,000` is intentionally
         * rejected because commands split arguments on spaces and grouping is output-only.
         *
         * Negative values and more than two fraction digits are rejected; no rounding is performed.
         */
        fun of(input: String): Money {
            val value = input.trim()
            if (value.isEmpty()) throw MoneyFormatException("Amount must not be empty.")
            if (value.startsWith('-')) throw MoneyFormatException("Amount cannot be negative.")
            if (!AMOUNT_PATTERN.matches(value)) {
                throw MoneyFormatException("'$input' is not a valid amount. Use up to two decimal places.")
            }

            val separator = value.indexOf('.')
            val whole = if (separator < 0) value else value.substring(0, separator)
            val fraction = if (separator < 0) "" else value.substring(separator + 1)
            val minorText = whole + fraction.padEnd(TitanCurrency.MINOR_UNIT_SCALE, '0')
            return try {
                Money(minorText.toLong())
            } catch (failure: NumberFormatException) {
                throw MoneyOverflowException("The amount '$input' is too large for the economy", failure)
            }
        }

        private val AMOUNT_PATTERN = Regex("""\d{1,18}(?:\.\d{1,2})?""")
    }
}

/** Central formatting used by every economy command, event, and audit view. */
fun formatMinorUnits(minorUnits: Long): String {
    val symbols = DecimalFormatSymbols(Locale.ROOT).apply {
        groupingSeparator = ','
        decimalSeparator = '.'
    }
    return DecimalFormat("#,##0.00", symbols).apply {
        roundingMode = RoundingMode.UNNECESSARY
        isGroupingUsed = true
    }.format(BigDecimal.valueOf(minorUnits, TitanCurrency.MINOR_UNIT_SCALE))
}

class MoneyFormatException(message: String) : IllegalArgumentException(message)
class MoneyOverflowException(message: String, cause: Throwable? = null) : ArithmeticException(message)
