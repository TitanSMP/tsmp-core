package de.titansmp.core.economy

import de.titansmp.api.economy.EconomyPermissionService
import net.luckperms.api.LuckPerms
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** LuckPerms API adapter; permissions, contexts, and wildcards are resolved by LuckPerms itself. */
class LuckPermsEconomyPermissionService(private val luckPerms: LuckPerms) : EconomyPermissionService {
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
