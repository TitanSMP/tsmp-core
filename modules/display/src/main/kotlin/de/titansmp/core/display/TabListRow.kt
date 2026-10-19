package de.titansmp.core.display

import de.titansmp.api.display.PlayerDisplayMeta
import java.util.UUID

/** One row of the network tab list: a player with resolved display meta. */
data class TabListRow(
    val playerUuid: UUID,
    val username: String,
    val meta: PlayerDisplayMeta,
) {
    /** Rendered TAB text: `prefix + name + clan suffix`. */
    val displayText: String get() = PlayerDisplayFormat.tabEntry(meta.prefix, username, meta.suffix)
}

/**
 * TAB ordering: higher LuckPerms weight first, players with the same weight alphabetically.
 *
 * The weight is always the LuckPerms value; no rank weight is hardcoded here.
 */
val TAB_LIST_ORDER: Comparator<TabListRow> =
    compareByDescending<TabListRow> { it.meta.weight }
        .thenBy { it.username.lowercase() }
