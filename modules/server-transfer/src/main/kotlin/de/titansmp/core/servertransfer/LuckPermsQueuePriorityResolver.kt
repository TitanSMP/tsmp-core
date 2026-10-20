package de.titansmp.core.servertransfer

import net.luckperms.api.LuckPerms
import net.luckperms.api.query.QueryOptions
import java.util.UUID

/**
 * Resolves `titansmp.server.priority.<number>` from LuckPerms' effective permission map.
 * Only this adapter knows about LuckPerms; queue ordering remains in [QueuePriorityPermissions].
 */
class LuckPermsQueuePriorityResolver(
    private val luckPerms: LuckPerms,
    private val defaultPriority: Int,
) : QueuePriorityResolver {
    init {
        require(defaultPriority in 0..de.titansmp.core.config.ServerTransferConfig.MAX_PRIORITY) {
            "Invalid default queue priority"
        }
    }

    override fun resolve(playerUuid: UUID): Int {
        val user = luckPerms.userManager.getUser(playerUuid) ?: return defaultPriority
        val permissions = try {
            user.cachedData.getPermissionData(QueryOptions.defaultContextualOptions()).permissionMap
        } catch (_: RuntimeException) {
            return defaultPriority
        }
        return QueuePriorityPermissions.highest(permissions, defaultPriority)
    }
}
