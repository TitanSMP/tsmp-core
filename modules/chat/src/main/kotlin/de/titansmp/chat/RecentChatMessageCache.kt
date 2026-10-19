package de.titansmp.chat

import java.time.Clock
import java.time.Duration
import java.util.LinkedHashMap
import java.util.UUID

/** Bounded TTL cache that admits a message ID once across a subscriber's lifetime. */
class RecentChatMessageCache(
    private val capacity: Int = 4_096,
    private val ttl: Duration = Duration.ofMinutes(5),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val seen = LinkedHashMap<UUID, Long>()

    init { require(capacity > 0); require(!ttl.isNegative && !ttl.isZero) }

    @Synchronized
    fun markIfNew(messageId: UUID): Boolean {
        val now = clock.millis()
        val cutoff = now - ttl.toMillis()
        val iterator = seen.entries.iterator()
        while (iterator.hasNext()) if (iterator.next().value <= cutoff) iterator.remove()
        if (seen.containsKey(messageId)) return false
        seen[messageId] = now
        while (seen.size > capacity) seen.remove(seen.keys.first())
        return true
    }
}
