package de.titansmp.core.discord.link

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Generates, normalizes, and hashes single-use link codes.
 *
 * Codes are drawn from a 32-character alphabet without ambiguous characters (`0/O`, `1/I`) and are 10
 * characters long, which is roughly 50 bits of entropy. There is no sequence, timestamp, or UUID
 * component, so codes cannot be guessed from each other. Only [hash] of the normalized code is ever
 * persisted; the plaintext code exists in memory and in the in-game message only.
 */
object DiscordLinkCodes {
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val LENGTH = 10
    private const val GROUP = 5

    private val random = SecureRandom()

    /** Generates a fresh code. The result contains no separators and is safe to hash directly. */
    fun generate(): String = buildString(LENGTH) {
        repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }

    /** Renders a code for display; separators are cosmetic and removed by [normalize]. */
    fun display(code: String): String = code.chunked(GROUP).joinToString("-")

    /**
     * Normalizes user input so it maps to the stored hash: separators and whitespace are removed and
     * the result is upper-cased. An input that does not match the code alphabet simply produces a hash
     * that no token matches, which surfaces as an invalid code.
     */
    fun normalize(input: String): String = input.filter { it.isLetterOrDigit() }.uppercase()

    /** SHA-256 hex digest of the normalized code. Only this value is stored. */
    fun hash(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(normalize(input).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
