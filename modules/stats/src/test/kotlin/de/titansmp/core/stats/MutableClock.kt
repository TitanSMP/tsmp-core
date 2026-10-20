package de.titansmp.core.stats

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A deterministic clock whose instant can be advanced explicitly in tests. */
class MutableClock(
    private var current: Instant = Instant.parse("2026-10-20T10:00:00Z"),
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)
    override fun instant(): Instant = current

    fun advance(duration: Duration) {
        current = current.plus(duration)
    }

    fun advanceMillis(millis: Long) {
        current = current.plusMillis(millis)
    }
}
