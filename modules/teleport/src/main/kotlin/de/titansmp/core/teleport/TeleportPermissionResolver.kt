package de.titansmp.core.teleport

/** Resolves numeric LuckPerms permission suffixes without knowing rank names or the platform API. */
object TeleportPermissionResolver {
    fun cooldownMinutes(grantedNodes: Iterable<String>, defaultMinutes: Int): Int =
        numericValues(grantedNodes, "titansmp.teleport.cooldown.").maxOrNull() ?: defaultMinutes

    /** 0 is the unlimited sentinel and wins over every finite limit. */
    fun homeLimit(grantedNodes: Iterable<String>, defaultLimit: Int): Int = resolveLimit(grantedNodes, "titansmp.teleport.homes.", defaultLimit)

    fun clanHomeLimit(adminGrantedNodes: Iterable<Iterable<String>>, defaultLimit: Int): Int {
        val values = adminGrantedNodes.flatMap { numericValues(it, "titansmp.teleport.clan.homes.") }
        return if (0 in values) 0 else values.maxOrNull() ?: defaultLimit
    }

    private fun resolveLimit(nodes: Iterable<String>, prefix: String, defaultValue: Int): Int {
        val values = numericValues(nodes, prefix)
        return if (0 in values) 0 else values.maxOrNull() ?: defaultValue
    }

    private fun numericValues(nodes: Iterable<String>, prefix: String): List<Int> = nodes.mapNotNull { node ->
        val suffix = node.takeIf { it.startsWith(prefix) }?.removePrefix(prefix) ?: return@mapNotNull null
        if (suffix.isEmpty() || suffix.any { !it.isDigit() }) return@mapNotNull null
        suffix.toIntOrNull()?.takeIf { it >= 0 }
    }
}

object TeleportCooldownKeys {
    fun key(playerUuid: java.util.UUID, category: de.titansmp.api.teleport.TeleportCooldownCategory, scope: String? = null): String =
        "titansmp:teleport:cooldown:$playerUuid:${category.name}:${scope?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: "-"}"
}
