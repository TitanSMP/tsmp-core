package de.titansmp.core.moderation

import java.time.Duration

object ModerationDurationParser {
    private val syntax = Regex("^([1-9][0-9]*)([smhdw])$", RegexOption.IGNORE_CASE)

    /** Returns null for permanent; malformed, zero, and overflowing values are rejected. */
    fun parse(value: String?): Duration? {
        if (value == null) return null
        val match = syntax.matchEntire(value) ?: throw InvalidModerationDurationException(value)
        val amount = match.groupValues[1].toLongOrNull() ?: throw InvalidModerationDurationException(value)
        val secondsPerUnit = when (match.groupValues[2].lowercase()) {
            "s" -> 1L
            "m" -> 60L
            "h" -> 3_600L
            "d" -> 86_400L
            "w" -> 604_800L
            else -> throw InvalidModerationDurationException(value)
        }
        return try { Duration.ofSeconds(Math.multiplyExact(amount, secondsPerUnit)) }
        catch (_: ArithmeticException) { throw InvalidModerationDurationException(value) }
    }
}

class InvalidModerationDurationException(value: String) : IllegalArgumentException("Invalid duration '$value'. Use values such as 30s, 10m, 2h, 7d, or 1w.")
