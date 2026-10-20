package de.titansmp.core.config

/**
 * Configuration for the network-wide server transfer and queue system.
 *
 * Backend names and server types are configuration values. Production code must never contain
 * literal backend names; Velocity's registered servers are mapped to types by [backends].
 */
class ServerTransferConfig(
    val enabled: Boolean = false,
    val defaultPriority: Int = 0,
    val actionbarIntervalMs: Long = 1_000,
    val reservationTimeoutMs: Long = 5_000,
    val processingIntervalMs: Long = 1_000,
    val aliases: Map<String, String> = emptyMap(),
    val types: Map<String, ServerTransferTypeConfig> = emptyMap(),
    val backends: Map<String, ServerTransferBackendConfig> = emptyMap(),
) {
    init {
        require(defaultPriority in 0..MAX_PRIORITY) {
            "server-transfer.default-priority must be between 0 and $MAX_PRIORITY"
        }
        require(actionbarIntervalMs in 250..60_000) {
            "server-transfer.queue.actionbar-interval-ms must be between 250 and 60000"
        }
        require(reservationTimeoutMs in 250..120_000) {
            "server-transfer.queue.reservation-timeout-ms must be between 250 and 120000"
        }
        require(processingIntervalMs in 100..60_000) {
            "server-transfer.queue.processing-interval-ms must be between 100 and 60000"
        }

        val normalizedTypeNames = types.keys.map { it.lowercase() }
        require(normalizedTypeNames.size == normalizedTypeNames.toSet().size) {
            "server-transfer.types contains duplicate server type names (server type names are case-insensitive)"
        }
        val normalizedAliases = aliases.keys.map { it.lowercase() }
        require(normalizedAliases.size == normalizedAliases.toSet().size) {
            "server-transfer.aliases contains duplicate alias names (aliases are case-insensitive)"
        }
        val normalizedBackends = backends.keys.map { it.lowercase() }
        require(normalizedBackends.size == normalizedBackends.toSet().size) {
            "server-transfer.backends contains duplicate backend names (backend names are case-insensitive)"
        }

        types.forEach { (type, settings) ->
            require(type.matches(TYPE_NAME_PATTERN)) {
                "Invalid server type '$type'; expected [a-z0-9][a-z0-9_-]{0,63}"
            }
            require(settings.maxPlayers > 0) {
                "server-transfer.types.$type.max-players must be positive"
            }
        }
        aliases.forEach { (alias, target) ->
            require(alias.matches(TYPE_NAME_PATTERN)) {
                "Invalid server transfer alias '$alias'; expected [a-z0-9][a-z0-9_-]{0,63}"
            }
            val type = types[target]
                ?: throw ConfigurationException("server-transfer.aliases.$alias references unknown server type '$target'")
            if (!type.enabled) {
                throw ConfigurationException("server-transfer.aliases.$alias references disabled server type '$target'")
            }
        }
        backends.forEach { (backend, mapping) ->
            require(backend.matches(BACKEND_NAME_PATTERN)) {
                "Invalid backend name '$backend'; expected [A-Za-z0-9_.-]{1,64}"
            }
            val type = types[mapping.type]
                ?: throw ConfigurationException("server-transfer.backends.$backend.type references unknown server type '${mapping.type}'")
            if (!type.enabled) {
                throw ConfigurationException("server-transfer.backends.$backend.type references disabled server type '${mapping.type}'")
            }
        }
        if (enabled) {
            require(types.isNotEmpty()) { "server-transfer.enabled=true requires at least one server type" }
        }
    }

    fun typeSettings(serverType: String): ServerTransferTypeConfig? =
        types[serverType.lowercase()]?.takeIf { it.enabled }

    fun isEnabledType(serverType: String): Boolean = typeSettings(serverType) != null

    /** Maps a registered backend name to its configured type. Unmapped backends are invisible. */
    fun typeForBackend(backendName: String): String? {
        val mapping = backends[backendName] ?: return null
        return mapping.type.takeIf { isEnabledType(it) }
    }

    /** Resolves an alias such as `lobby` or `hub` without hardcoding a backend name. */
    fun aliasTarget(alias: String): String? = aliases[alias.lowercase()]?.takeIf { isEnabledType(it) }

    /** Enabled server type names in deterministic order for command completion and diagnostics. */
    fun enabledTypes(): List<String> = types.filterValues { it.enabled }.keys.sorted()

    override fun toString(): String =
        "ServerTransferConfig(enabled=$enabled, defaultPriority=$defaultPriority, " +
            "actionbarIntervalMs=$actionbarIntervalMs, reservationTimeoutMs=$reservationTimeoutMs, " +
            "processingIntervalMs=$processingIntervalMs, aliases=$aliases, types=$types, backends=$backends)"

    companion object {
        const val MAX_PRIORITY = 1_000_000
        const val PRIORITY_PERMISSION_PREFIX = "titansmp.server.priority."
        private val TYPE_NAME_PATTERN = Regex("[a-z0-9][a-z0-9_-]{0,63}")
        private val BACKEND_NAME_PATTERN = Regex("[A-Za-z0-9_.-]{1,64}")
    }
}

data class ServerTransferTypeConfig(
    val enabled: Boolean = true,
    val maxPlayers: Int,
) {
    init {
        require(maxPlayers > 0) { "Server type max-players must be positive" }
    }
}

data class ServerTransferBackendConfig(
    val type: String,
)
