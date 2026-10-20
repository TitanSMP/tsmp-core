package de.titansmp.core.stats

import de.titansmp.api.stats.StatType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Accumulates fractional values locally and drains whole units.
 *
 * Paper reports block distance and damage as `double`. A single movement event is usually well under
 * one block and a single hit is often half a heart, so rounding each event to a whole number would
 * lose nearly all data. Values are summed as `double` and converted to whole units only when they are
 * flushed. The sub-unit remainder is kept across flushes, so nothing is lost when a total is not yet
 * a whole unit.
 *
 * The accumulator is thread-safe; a single instance is shared for a given unit (blocks or health
 * points).
 */
class FractionalAccumulator {
    private class Entry {
        @Volatile var value: Double = 0.0
    }

    private val entries = ConcurrentHashMap<UUID, ConcurrentHashMap<StatType, Entry>>()

    /** Adds [amount] for a statistic. Non-positive amounts are ignored. */
    fun record(playerUuid: UUID, statType: StatType, amount: Double) {
        if (amount <= 0.0) return
        entries.computeIfAbsent(playerUuid) { ConcurrentHashMap() }
            .computeIfAbsent(statType) { Entry() }
            .let { entry -> synchronized(entry) { entry.value += amount } }
    }

    /**
     * Drains every accumulated value and returns whole-unit amounts. Each player/statistic keeps its
     * sub-unit remainder for the next drain, so rounding never drops data.
     */
    fun drain(): Map<UUID, Map<StatType, Long>> {
        val result = LinkedHashMap<UUID, Map<StatType, Long>>()
        entries.entries.forEach { (playerUuid, perPlayer) ->
            val values = LinkedHashMap<StatType, Long>()
            perPlayer.entries.forEach { (statType, entry) ->
                val whole = synchronized(entry) {
                    val current = entry.value
                    val wholeUnits = current.toLong()
                    entry.value = current - wholeUnits
                    wholeUnits
                }
                if (whole > 0) values[statType] = whole
            }
            if (values.isNotEmpty()) result[playerUuid] = values
        }
        return result
    }

    /** The current accumulated (unflushed) value, for diagnostics. */
    fun pendingValue(playerUuid: UUID, statType: StatType): Double =
        entries[playerUuid]?.get(statType)?.let { entry -> synchronized(entry) { entry.value } } ?: 0.0
}
