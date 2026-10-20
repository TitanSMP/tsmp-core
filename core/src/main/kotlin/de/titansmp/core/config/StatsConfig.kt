package de.titansmp.core.config

import de.titansmp.api.stats.StatType

/**
 * Configuration for the network-wide player statistics system.
 *
 * Tracking is fully configuration-driven. Each statistic can be enabled/disabled independently and
 * can be restricted to a set of **server identity types** (the configured `server.type` of a server,
 * for example `SMP` or `LOBBY`). No concrete server name (`smp-01`, `lobby-02`, ...) and no
 * hardcoded type name ever appears in production code; the values in `config/titansmp.properties`
 * are examples only.
 */
class StatsConfig(
    val enabled: Boolean = false,
    val flushIntervalMs: Long = 5_000,
    val tracking: Map<StatType, StatTrackingConfig> = defaultTracking(),
) {
    init {
        require(flushIntervalMs in 500..300_000) {
            "stats.flush-interval-ms must be between 500 and 300000"
        }
        require(tracking.keys.containsAll(StatType.entries.toSet())) {
            "stats.tracking must define every statistic"
        }
    }

    /** Tracking settings for a statistic, or the disabled default when the statistic is unknown. */
    fun trackingFor(statType: StatType): StatTrackingConfig =
        tracking[statType] ?: StatTrackingConfig(enabled = false)

    /**
     * Whether [statType] should be collected on a server whose configured identity type is
     * [serverIdentityType]. Matching is case-insensitive and entirely configuration-driven.
     */
    fun isTrackedOn(statType: StatType, serverIdentityType: String): Boolean {
        val settings = trackingFor(statType)
        if (!settings.enabled) return false
        if (settings.scope == StatScope.NETWORK) return true
        return settings.serverTypes.any { it.equals(serverIdentityType, ignoreCase = true) }
    }

    override fun toString(): String =
        "StatsConfig(enabled=$enabled, flushIntervalMs=$flushIntervalMs, tracking=$tracking)"

    companion object {
        /** Every statistic is disabled by default until a network opts in explicitly. */
        fun defaultTracking(): Map<StatType, StatTrackingConfig> =
            StatType.entries.associateWith { StatTrackingConfig(enabled = false) }
    }
}

/** Whether a statistic is collected network-wide or only on the configured server types. */
enum class StatScope {
    /** Collected on every server, for example the network-wide join counter. */
    NETWORK,

    /** Collected only on servers whose identity type is listed in [StatTrackingConfig.serverTypes]. */
    SERVER_TYPES,
}

/**
 * Per-statistic tracking configuration.
 *
 * [serverTypes] is ignored when [scope] is [StatScope.NETWORK]. An empty [serverTypes] list with
 * [StatScope.SERVER_TYPES] means the statistic is enabled but collected nowhere, which is validated
 * as a configuration error by the loader.
 */
data class StatTrackingConfig(
    val enabled: Boolean = false,
    val scope: StatScope = StatScope.SERVER_TYPES,
    val serverTypes: Set<String> = emptySet(),
) {
    init {
        require(serverTypes.none { it.isBlank() }) { "stats server types must not be blank" }
        require(serverTypes.all { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) }) {
            "Invalid stats server type; expected [A-Za-z0-9][A-Za-z0-9_-]{0,63}"
        }
    }
}
