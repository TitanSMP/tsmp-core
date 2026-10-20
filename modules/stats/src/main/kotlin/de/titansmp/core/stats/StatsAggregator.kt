package de.titansmp.core.stats

import de.titansmp.api.stats.StatType
import de.titansmp.api.stats.StatsService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Local, thread-safe counter that batches statistics before they are flushed to [StatsService].
 *
 * The game server records high-frequency events (block breaks, movement, damage, kills) with
 * [record], which only increments an in-memory `LongAdder`-style counter. [flush] then applies the
 * accumulated deltas to MariaDB in one call per player. This is what keeps the implementation from
 * writing to MariaDB on every tick, block, or damage event.
 *
 * The counters are drained atomically per player, so a concurrent [record] during a flush is never
 * lost: it lands in the next flush. Callers must drain their own pending counters before shutdown so
 * playtime and combat accumulated since the last flush are not dropped.
 */
class StatsAggregator(
    private val statsService: StatsService,
    private val logger: Logger = Logger.getLogger(StatsAggregator::class.java.name),
) {
    private val pending = ConcurrentHashMap<UUID, ConcurrentHashMap<StatType, Counter>>()

    /**
     * A single tracked statistic's accumulated counter. [get] and [add] are lock-free; [drain]
     * resets the counter and returns the value atomically, so nothing recorded mid-flush is lost.
     */
    class Counter {
        private val value = AtomicLong()
        fun add(delta: Long) { if (delta > 0) value.addAndGet(delta) }
        fun get(): Long = value.get()
        fun drain(): Long = value.getAndSet(0)
    }

    /** Records [delta] for a statistic without touching the database. [delta] must not be negative. */
    fun record(playerUuid: UUID, statType: StatType, delta: Long) {
        if (delta <= 0) return
        val perPlayer = pending.computeIfAbsent(playerUuid) { ConcurrentHashMap() }
        perPlayer.computeIfAbsent(statType) { Counter() }.add(delta)
    }

    /** The number of players with at least one unflushed statistic. */
    fun pendingPlayers(): Int = pending.size

    /** The player's currently accumulated, unflushed value for a statistic. */
    fun pendingValue(playerUuid: UUID, statType: StatType): Long =
        pending[playerUuid]?.get(statType)?.get() ?: 0

    /**
     * Drains and persists every accumulated counter, one call per player. A failed player write is
     * logged and its drained deltas are discarded (statistics are best-effort counters); a later
     * flush is unaffected. Running with no pending counters is a cheap no-op.
     */
    fun flush() {
        if (pending.isEmpty()) return
        val drained = drainAll()
        drained.forEach { (playerUuid, deltas) ->
            if (deltas.isEmpty()) return@forEach
            statsService.incrementAll(playerUuid, deltas).whenComplete { _, failure ->
                if (failure != null) {
                    logger.log(Level.SEVERE, "Could not flush statistics for $playerUuid: ${failure.message}", failure)
                }
            }
        }
    }

    /** Synchronously drains all counters. Used by [flush] and on shutdown. */
    private fun drainAll(): Map<UUID, Map<StatType, Long>> {
        val drained = LinkedHashMap<UUID, Map<StatType, Long>>()
        // Remove each per-player map before draining it. A concurrent record after removal creates a
        // fresh map (via computeIfAbsent) that the next flush picks up, so nothing is lost or
        // double-counted. A record racing with drain() lands in the currently drained map and is
        // captured by drain(); the counter is reset atomically.
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            val (playerUuid, perPlayer) = iterator.next()
            iterator.remove()
            val values = LinkedHashMap<StatType, Long>()
            perPlayer.entries.forEach { (statType, counter) ->
                val value = counter.drain()
                if (value > 0) values[statType] = value
            }
            if (values.isNotEmpty()) drained[playerUuid] = values
        }
        return drained
    }
}
