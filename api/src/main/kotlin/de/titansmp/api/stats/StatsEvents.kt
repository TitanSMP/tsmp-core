package de.titansmp.api.stats

import de.titansmp.api.event.EventMetadata
import de.titansmp.api.event.EventType
import de.titansmp.api.event.NetworkEvent
import java.util.UUID

/**
 * Published after an aggregated statistics flush committed to MariaDB.
 *
 * This event is deliberately **aggregated**: it is published when a batch of locally collected
 * counters is persisted, never for an individual block, step, or damage tick. It carries the
 * network-wide totals after the flush so the future web API and other modules can invalidate caches
 * without re-reading MariaDB, and it is idempotently processable because it carries absolute values
 * rather than deltas.
 *
 * Redis carries only these aggregated notifications. Statistics themselves are never synchronized
 * per increment over Redis; MariaDB is the durable source of truth.
 */
data class PlayerStatsUpdated(
    override val metadata: EventMetadata,
    val playerUuid: UUID,
    val stats: PlayerStats,
) : NetworkEvent {
    companion object {
        val TYPE = EventType("stats.player_updated")
    }
}
