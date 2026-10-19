package de.titansmp.core.display

import de.titansmp.api.display.PlayerDisplayMeta

/**
 * The single place that turns display meta into text.
 *
 * TAB, chat, and nametags must all route through these functions so the prefix is resolved and rendered
 * identically everywhere instead of each component inventing its own formatting.
 *
 * Rules:
 * - TAB: `prefix + name + clan suffix`
 * - Chat: `prefix + name: message` (never the clan suffix)
 * - Nametag: `prefix + name` (never the clan suffix)
 */
object PlayerDisplayFormat {
    /** Nametag/text rule: `prefix + name`, no clan suffix. */
    fun nameTag(prefix: String?, name: String): String = join(prefix, name)

    /** TAB rule: `prefix + name + clan suffix`. */
    fun tabEntry(prefix: String?, name: String, suffix: String?): String = join(prefix, name, suffix)

    /** Chat rule: `prefix + name: message`, never the clan suffix. */
    fun chatMessage(prefix: String?, name: String, message: String): String = nameTag(prefix, name) + ": " + message

    /**
     * Paper scoreboard-team prefix for the nametag rule. The client renders the player name directly after
     * this value, so a non-blank prefix keeps a single trailing space.
     */
    fun nameTagPrefix(prefix: String?): String = prefix?.takeIf { it.isNotBlank() }?.plus(" ") ?: ""

    fun nameTag(meta: PlayerDisplayMeta?, name: String): String = nameTag(meta?.prefix, name)

    fun tabEntry(meta: PlayerDisplayMeta?, name: String): String = tabEntry(meta?.prefix, name, meta?.suffix)

    private fun join(vararg parts: String?): String =
        parts.mapNotNull { it?.takeIf(String::isNotBlank) }.joinToString(" ")
}
