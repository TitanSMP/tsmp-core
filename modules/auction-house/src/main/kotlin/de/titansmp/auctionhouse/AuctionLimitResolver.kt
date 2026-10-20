package de.titansmp.auctionhouse

object AuctionLimitResolver {
    private val pattern = Regex("^titansmp\\.auction\\.limit\\.(\\d+)$")

    /** Highest non-negative numeric grant wins; 0 means unlimited. */
    fun resolve(permissions: Iterable<String>, defaultLimit: Int): Int {
        require(defaultLimit >= 0)
        val values = permissions.mapNotNull { permission ->
            pattern.matchEntire(permission)?.groupValues?.get(1)?.toIntOrNull()
        }
        return if (0 in values) 0 else values.maxOrNull() ?: defaultLimit
    }
}
