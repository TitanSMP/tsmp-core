package de.titansmp.core.display

import java.util.UUID
import java.util.concurrent.CompletionStage

/**
 * Raw rank data read from the permission source.
 *
 * The clan suffix is intentionally absent: it never comes from LuckPerms. See `DisplaySuffixProvider`.
 */
data class RankSnapshot(
    val prefix: String?,
    val weight: Int,
)

/**
 * Isolates the permission-source calls so [LuckPermsDisplayProvider] stays unit-testable without a live
 * LuckPerms instance. Production code uses [LuckPermsRankSource].
 */
interface RankSource {
    /** Returns a snapshot only when the player data is already available, otherwise `null`. */
    fun cached(playerUuid: UUID): RankSnapshot?

    /** Loads player data when necessary and returns the snapshot. */
    fun load(playerUuid: UUID): CompletionStage<RankSnapshot>
}
