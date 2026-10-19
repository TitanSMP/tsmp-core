package de.titansmp.core.moderation

import de.titansmp.api.moderation.ModerationPermissionService
import net.luckperms.api.LuckPerms
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** LuckPerms API adapter shared by Paper and Velocity; queries use LuckPerms contexts and wildcard rules. */
class LuckPermsModerationPermissionService(private val luckPerms: LuckPerms) : ModerationPermissionService {
    override fun hasPermission(playerId: UUID, permission: String): CompletionStage<Boolean> {
        val loaded = luckPerms.userManager.getUser(playerId)
        if (loaded != null) return CompletableFuture.completedFuture(check(loaded, playerId, permission))
        return luckPerms.userManager.loadUser(playerId).thenApply { user -> check(user, playerId, permission) }
    }

    private fun check(user: net.luckperms.api.model.user.User, playerId: UUID, permission: String): Boolean {
        val options = luckPerms.contextManager.getQueryOptions(user).orElse(luckPerms.contextManager.staticQueryOptions)
        return user.cachedData.getPermissionData(options).checkPermission(permission).asBoolean()
    }
}
