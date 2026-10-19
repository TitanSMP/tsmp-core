package de.titansmp.api.display

import java.util.UUID
import java.util.concurrent.CompletionStage

/**
 * Resolves rank/display data for a player.
 *
 * The platform implementation is backed by LuckPerms, which is the single source of truth for prefix,
 * metadata, and rank weight. Display code must never read LuckPerms directly; it goes through
 * [PlayerDisplayService].
 */
fun interface DisplayProvider {
    fun displayMeta(playerUuid: UUID): CompletionStage<PlayerDisplayMeta>
}

/**
 * Optional source of a clan suffix.
 *
 * No implementation exists until the future `modules:clans` module provides clan tags. When it does,
 * the suffix flows into [PlayerDisplayMeta.suffix] and is only rendered by the TAB list.
 */
fun interface DisplaySuffixProvider {
    fun clanSuffix(playerUuid: UUID): CompletionStage<String?>
}

/**
 * Central read model for TAB, chat, and nametags.
 *
 * The service combines the LuckPerms [DisplayProvider] with any registered [DisplaySuffixProvider]s,
 * caches the result per UUID, and publishes [PlayerDisplayMetaChanged] so other components can react.
 * This is the only place where display data is assembled, which prevents TAB, chat, and nametags from
 * implementing their own prefix logic.
 */
interface PlayerDisplayService {
    /** Returns display meta, resolving and caching it on a cache miss. */
    fun displayMeta(playerUuid: UUID): CompletionStage<PlayerDisplayMeta>

    /** Returns the last cached meta without resolving, or `null` when the player is unknown. */
    fun cachedMeta(playerUuid: UUID): PlayerDisplayMeta?

    /** Re-resolves display meta and publishes a change event when the result differs from the cache. */
    fun refresh(playerUuid: UUID): CompletionStage<PlayerDisplayMeta>

    /** Drops cached meta; the next [displayMeta] resolves it again. */
    fun invalidate(playerUuid: UUID)

    /** UUIDs that currently have cached meta. */
    fun cachedPlayers(): Set<UUID>
}
