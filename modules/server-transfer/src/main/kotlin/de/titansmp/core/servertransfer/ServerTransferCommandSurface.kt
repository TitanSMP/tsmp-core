package de.titansmp.core.servertransfer

import de.titansmp.core.config.ServerTransferConfig

/**
 * The only commands TitanSMP registers for this feature. Velocity's native `/server` command is
 * deliberately absent: it remains authoritative for its own `velocity.command.server` permission.
 */
object ServerTransferCommandSurface {
    const val JOIN = "join"
    const val LOBBY = "lobby"
    const val HUB = "hub"

    val COMMAND_NAMES: Set<String> = linkedSetOf(JOIN, LOBBY, HUB)
}

object ServerTransferAliases {
    /** Resolves `/lobby` or `/hub` through configuration; no backend or type is hardcoded. */
    fun resolve(config: ServerTransferConfig, commandName: String): String? =
        config.aliasTarget(commandName)
}

object QueueActionBarFormatter {
    fun format(position: Int, size: Int): String {
        require(position >= 1) { "Queue position must be positive" }
        require(size >= position) { "Queue size must not be smaller than the position" }
        return "Queue position: $position / $size"
    }
}
