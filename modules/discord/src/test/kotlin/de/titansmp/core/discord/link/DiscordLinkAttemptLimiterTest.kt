package de.titansmp.core.discord.link

import de.titansmp.core.discord.MutableClock
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscordLinkAttemptLimiterTest {
    private val clock = MutableClock(Instant.parse("2026-10-19T20:00:00Z"))

    @Test
    fun `blocks attempts beyond the configured maximum within the window`() {
        val limiter = DiscordLinkAttemptLimiter(maxAttempts = 3, windowSeconds = 60, clock = clock)
        assertTrue(limiter.tryAcquire(1L))
        assertTrue(limiter.tryAcquire(1L))
        assertTrue(limiter.tryAcquire(1L))
        assertFalse(limiter.tryAcquire(1L))
    }

    @Test
    fun `window resets after the configured duration`() {
        val limiter = DiscordLinkAttemptLimiter(maxAttempts = 1, windowSeconds = 30, clock = clock)
        assertTrue(limiter.tryAcquire(7L))
        assertFalse(limiter.tryAcquire(7L))
        clock.advance(Duration.ofSeconds(31))
        assertTrue(limiter.tryAcquire(7L))
    }

    @Test
    fun `limits are tracked per discord user`() {
        val limiter = DiscordLinkAttemptLimiter(maxAttempts = 1, windowSeconds = 60, clock = clock)
        assertTrue(limiter.tryAcquire(1L))
        assertFalse(limiter.tryAcquire(1L))
        assertTrue(limiter.tryAcquire(2L))
    }
}
