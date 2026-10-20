package de.titansmp.core.stats

import de.titansmp.api.stats.StatType
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Measures per-player playtime locally so no time is lost or double-counted across a network
 * transfer.
 *
 * A player's local session starts at [start] (Paper join) and is converted to whole seconds at
 * [stop] (Paper quit). Because a network transfer always fires a quit on the source server before
 * the join on the target server, each second of playtime is attributed to exactly one server session
 * and flushed by [StatsAggregator]. A server shutdown calls [stopAll] so an abrupt stop still
 * accounts for the open session.
 *
 * Playtime is only counted when [enabled] is `true`, which the caller derives from the configured
 * server identity type; on a server whose type is not listed for `playtime`, this tracker records
 * nothing.
 */
class PlaytimeTracker(
    private val enabled: Boolean,
    private val aggregator: StatsAggregator,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val sessions = ConcurrentHashMap<UUID, Long>()

    /** Begins a playtime session; a repeated join keeps the earliest start time. */
    fun start(playerUuid: UUID) {
        if (!enabled) return
        sessions.putIfAbsent(playerUuid, clock.millis())
    }

    /** Ends a session and records the whole seconds elapsed since [start]. Returns the seconds added. */
    fun stop(playerUuid: UUID): Long {
        if (!enabled) return 0
        val startedAt = sessions.remove(playerUuid) ?: return 0
        val seconds = elapsedSeconds(startedAt, clock.millis())
        if (seconds > 0) aggregator.record(playerUuid, StatType.PLAYTIME, seconds)
        return seconds
    }

    /** Ends every open session; used on shutdown so an abrupt stop does not lose playtime. */
    fun stopAll() {
        if (!enabled) return
        sessions.keys.toList().forEach(::stop)
    }

    /** The number of currently open sessions. */
    fun openSessions(): Int = sessions.size

    private fun elapsedSeconds(startedAtMillis: Long, nowMillis: Long): Long {
        val elapsed = nowMillis - startedAtMillis
        if (elapsed <= 0) return 0
        return elapsed / 1_000
    }
}
