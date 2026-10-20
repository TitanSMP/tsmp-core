package de.titansmp.core.servertransfer

import de.titansmp.core.config.ServerTransferConfig
import java.util.UUID

/** Isolates queue-priority resolution from LuckPerms so ordering rules are unit-testable. */
fun interface QueuePriorityResolver {
    fun resolve(playerUuid: UUID): Int
}

object QueuePriorityPermissions {
    const val PREFIX = ServerTransferConfig.PRIORITY_PERMISSION_PREFIX

    /**
     * Returns the highest granted numeric priority at or above [defaultPriority].
     * Invalid or explicitly denied nodes are ignored.
     */
    fun highest(permissions: Map<String, Boolean>, defaultPriority: Int = 0): Int {
        require(defaultPriority in 0..ServerTransferConfig.MAX_PRIORITY) { "Invalid default priority" }
        var highest = defaultPriority
        permissions.forEach { (node, granted) ->
            if (granted && node.startsWith(PREFIX)) {
                val candidate = node.removePrefix(PREFIX).toIntOrNull()
                if (candidate != null && candidate in 0..ServerTransferConfig.MAX_PRIORITY) {
                    if (candidate > highest) highest = candidate
                }
            }
        }
        return highest
    }
}
