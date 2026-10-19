package de.titansmp.core.discord.link

import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/**
 * Fixed-window, in-memory limiter for Discord link attempts, keyed by Discord user ID.
 *
 * The limiter only throttles brute-force attempts against link codes; it is intentionally not persisted
 * and does not create a database entry per attempt. A restart therefore resets the window, which is
 * acceptable because link codes are short-lived and high-entropy.
 */
class DiscordLinkAttemptLimiter(
    private val maxAttempts: Int,
    private val windowSeconds: Int,
    private val clock: Clock = Clock.systemUTC(),
) {
    private data class Window(var startedAt: Long, var count: Int)

    private val windows = ConcurrentHashMap<Long, Window>()

    init {
        require(maxAttempts > 0) { "Discord link max attempts must be positive" }
        require(windowSeconds > 0) { "Discord link attempt window must be positive" }
    }

    /** Returns `true` when the attempt is allowed, `false` when the caller is rate limited. */
    fun tryAcquire(discordUserId: Long): Boolean {
        val now = clock.millis()
        val windowMs = windowSeconds * 1_000L
        windows.entries.removeIf { now - it.value.startedAt >= windowMs * 2 }
        var allowed = false
        windows.compute(discordUserId) { _, current ->
            when {
                current == null || now - current.startedAt >= windowMs -> Window(now, 1).also { allowed = true }
                current.count < maxAttempts -> current.apply { count++; allowed = true }
                else -> current
            }
        }
        return allowed
    }
}
