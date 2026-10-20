package de.titansmp.core.stats

import de.titansmp.api.stats.StatType
import de.titansmp.core.config.StatsConfig

/**
 * Resolves whether a statistic is collected on this server.
 *
 * The policy is built from the central [StatsConfig] and this server's configured identity type
 * (`server.type`, for example `SMP`). No concrete server name and no hardcoded type name appears
 * here: the set of eligible types is read entirely from configuration, matched case-insensitively.
 */
class StatsTrackingPolicy(
    private val config: StatsConfig,
    private val serverIdentityType: String,
) {
    val enabled: Boolean get() = config.enabled

    /** Whether [statType] is collected on this server. */
    fun tracks(statType: StatType): Boolean = config.enabled && config.isTrackedOn(statType, serverIdentityType)

    /** The enabled server identity types tracked for [statType]; for diagnostics only. */
    fun trackedServerTypes(statType: StatType): Set<String> = config.trackingFor(statType).serverTypes

    override fun toString(): String = "StatsTrackingPolicy(enabled=$enabled, serverIdentityType=$serverIdentityType)"
}
