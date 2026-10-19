package de.titansmp.core.display

import de.titansmp.api.display.DisplayProvider
import de.titansmp.api.display.PlayerDisplayMeta
import net.luckperms.api.LuckPerms
import net.luckperms.api.model.user.User
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * LuckPerms-backed [DisplayProvider].
 *
 * This is the only place that reads rank data for display, and the only place that touches the LuckPerms
 * API for display purposes. It uses LuckPerms' own user cache and never queries a database per update.
 * The clan suffix is left empty and is filled later by a `DisplaySuffixProvider`.
 */
class LuckPermsDisplayProvider(private val source: RankSource) : DisplayProvider {
    constructor(luckPerms: LuckPerms) : this(LuckPermsRankSource(luckPerms))

    override fun displayMeta(playerUuid: UUID): CompletionStage<PlayerDisplayMeta> {
        val cached = source.cached(playerUuid)
        if (cached != null) return CompletableFuture.completedFuture(cached.toDisplayMeta(playerUuid))
        return source.load(playerUuid).thenApply { it.toDisplayMeta(playerUuid) }
    }
}

private fun RankSnapshot.toDisplayMeta(playerUuid: UUID): PlayerDisplayMeta = PlayerDisplayMeta(
    playerUuid = playerUuid,
    prefix = prefix.orEmpty(),
    // The clan system supplies the suffix later; LuckPerms is not the suffix source.
    suffix = null,
    weight = weight,
)

/** Reads the prefix and rank weight from LuckPerms cached/loaded user data. */
class LuckPermsRankSource(private val luckPerms: LuckPerms) : RankSource {
    override fun cached(playerUuid: UUID): RankSnapshot? =
        luckPerms.userManager.getUser(playerUuid)?.let(::snapshot)

    override fun load(playerUuid: UUID): CompletionStage<RankSnapshot> =
        luckPerms.userManager.loadUser(playerUuid).thenApply(::snapshot)

    private fun snapshot(user: User): RankSnapshot {
        val options = luckPerms.contextManager.getQueryOptions(user).orElse(luckPerms.contextManager.staticQueryOptions)
        val meta = user.cachedData.getMetaData(options)
        val prefix = meta.prefix?.takeIf { it.isNotBlank() }
        // LuckPerms computes the primary group from the highest group weight; the weight itself is the
        // group's configured weight, never a value hardcoded here.
        val weight = meta.primaryGroup
            ?.takeIf { it.isNotBlank() }
            ?.let { luckPerms.groupManager.getGroup(it)?.weight?.orElse(0) }
            ?: 0
        return RankSnapshot(prefix, weight)
    }
}
