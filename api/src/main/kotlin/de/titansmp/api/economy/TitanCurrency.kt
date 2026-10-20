package de.titansmp.api.economy

/**
 * The single TitanSMP currency. There is intentionally no multi-currency abstraction: every balance
 * and audit row is denominated in TitanCoins.
 */
object TitanCurrency {
    const val ID = "TITANCOINS"
    const val DISPLAY_NAME = "TitanCoins"
    const val MINOR_UNITS_PER_COIN = 100L
    const val MINOR_UNIT_SCALE = 2

    /** Formats [amount] with the canonical two-decimal representation and currency suffix. */
    fun label(amount: Money): String = "${amount.format()} $DISPLAY_NAME"
}
