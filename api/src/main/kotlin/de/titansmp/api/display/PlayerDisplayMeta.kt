package de.titansmp.api.display

import java.util.UUID

/**
 * Platform-independent display data for one network player.
 *
 * [prefix] is the already-resolved rank prefix from LuckPerms. It may be empty when no prefix is set
 * and must be used verbatim by TAB, chat, and nametags so all three stay consistent.
 *
 * [suffix] is reserved for the future clan tag. It stays `null`/empty until a clan system provides a
 * value through a [DisplaySuffixProvider]; it is never read from LuckPerms.
 *
 * [weight] is the LuckPerms rank weight used for TAB ordering. Higher weights sort first. The value is
 * always supplied by LuckPerms and is never hardcoded.
 */
data class PlayerDisplayMeta(
    val playerUuid: UUID,
    val prefix: String = "",
    val suffix: String? = null,
    val weight: Int = 0,
) {
    /** True when a non-blank rank prefix is present. */
    val hasPrefix: Boolean get() = prefix.isNotBlank()

    /** True when a non-blank clan suffix is present. */
    val hasSuffix: Boolean get() = !suffix.isNullOrBlank()
}
