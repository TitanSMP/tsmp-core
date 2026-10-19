package de.titansmp.api.databridge

import java.util.UUID
import java.time.Instant
import java.time.Duration
import de.titansmp.api.event.EventMetadata
import de.titansmp.api.event.EventType
import de.titansmp.api.event.NetworkEvent

/** Opaque, Paper-produced ItemStack bytes. Null represents an empty slot. */
class SerializedPlayerItem(payload: ByteArray) {
    private val value = payload.copyOf()
    val payload: ByteArray get() = value.copyOf()

    init { require(value.isNotEmpty()) { "Serialized item payload must not be empty" } }
    override fun equals(other: Any?): Boolean = other is SerializedPlayerItem && value.contentEquals(other.value)
    override fun hashCode(): Int = value.contentHashCode()
}

/** Portable Phase 1 player state. Armor order is helmet, chestplate, leggings, boots. */
data class PlayerState(
    val playerUuid: UUID,
    val inventory: List<SerializedPlayerItem?>,
    val armor: List<SerializedPlayerItem?>,
    val offhand: SerializedPlayerItem?,
    val health: Double,
    val foodLevel: Int,
    val experience: Float,
    val level: Int,
    val totalExperience: Int,
    val revision: Long = 0,
    val potionEffects: List<PotionEffectData> = emptyList(),
    /** Wall-clock instant at which finite effect durations were captured. */
    val capturedAt: Instant = Instant.ofEpochMilli(System.currentTimeMillis()),
    val advancements: List<AdvancementProgressData> = emptyList(),
) {
    init {
        require(inventory.size == INVENTORY_SLOTS) { "Main inventory must contain $INVENTORY_SLOTS slots" }
        require(armor.size == ARMOR_SLOTS) { "Armor must contain $ARMOR_SLOTS slots" }
        require(health.isFinite() && health >= 0.0) { "Health must be finite and non-negative" }
        require(foodLevel in 0..20) { "Food level must be between 0 and 20" }
        require(experience.isFinite() && experience in 0.0f..1.0f) { "Experience progress must be between 0 and 1" }
        require(level >= 0 && totalExperience >= 0) { "Experience values cannot be negative" }
        require(revision >= 0) { "Revision cannot be negative" }
        require(potionEffects.map { it.type }.distinct().size == potionEffects.size) { "Potion effect types must be unique" }
        require(advancements.map { it.advancementKey }.distinct().size == advancements.size) { "Advancement keys must be unique" }
    }

    fun withRevision(value: Long): PlayerState = copy(revision = value)

    companion object {
        const val INVENTORY_SLOTS = 36
        const val ARMOR_SLOTS = 4
    }
}

/** Player-specific progress only; advancement definitions remain server-owned. */
data class AdvancementProgressData(
    val advancementKey: String,
    val criteria: List<CriterionProgressData>,
) {
    init {
        require(advancementKey.matches(Regex("[a-z0-9._-]+:[a-z0-9/._-]+"))) { "Advancement key must be namespaced" }
        require(criteria.map { it.criterionKey }.distinct().size == criteria.size) { "Criterion keys must be unique" }
    }
}

data class CriterionProgressData(val criterionKey: String, val completed: Boolean) {
    init { require(criterionKey.isNotBlank() && criterionKey.length <= 32_000) { "Invalid criterion key" } }
}

/** Platform-neutral effect snapshot. Durations are ticks; infinite effects retain their special semantics. */
data class PotionEffectData(
    val type: String,
    val durationTicks: Int,
    val amplifier: Int,
    val ambient: Boolean,
    val particles: Boolean,
    val icon: Boolean,
    val infinite: Boolean = false,
    val hiddenEffect: PotionEffectData? = null,
) {
    init {
        require(type.matches(Regex("[a-z0-9._-]+:[a-z0-9/._-]+"))) { "Potion effect type must be a namespaced identifier" }
        require(if (infinite) durationTicks == 0 else durationTicks >= 0) { "Invalid potion effect duration" }
        require(amplifier >= 0) { "Potion effect amplifier cannot be negative" }
    }

    /** Returns Paper's special infinite sentinel, remaining finite ticks, or null after expiration. */
    fun remainingTicksAt(capturedAt: Instant, now: Instant): Int? {
        if (infinite) return -1
        if (!now.isAfter(capturedAt)) return durationTicks.takeIf { it > 0 }
        val remainingWindowMillis = durationTicks.toLong() * 50L
        val elapsedMillis = try { Duration.between(capturedAt, now).toMillis() } catch (_: ArithmeticException) { Long.MAX_VALUE }
        if (elapsedMillis >= remainingWindowMillis) return null
        val elapsedTicks = (elapsedMillis + 49L) / 50L
        return (durationTicks - elapsedTicks.toInt()).takeIf { it > 0 }
    }
}

/** MariaDB-backed state service. Implementations perform blocking work asynchronously. */
interface PlayerDataService {
    fun load(playerUuid: UUID, serverId: String): java.util.concurrent.CompletionStage<PlayerState?>
    fun save(state: PlayerState, serverId: String): java.util.concurrent.CompletionStage<PlayerState>
}

class PlayerStateConflictException(playerUuid: UUID) :
    RuntimeException("Player state for $playerUuid changed elsewhere; stale revision rejected")

class PlayerStateOwnershipException(playerUuid: UUID, serverId: String) :
    RuntimeException("Server $serverId does not own the online player session for $playerUuid")

/** Notification only; consumers must ignore revisions they have already observed. */
data class PlayerStateUpdated(
    override val metadata: EventMetadata,
    val playerUuid: UUID,
    val revision: Long,
) : NetworkEvent {
    init { require(revision > 0) }
    companion object { val TYPE = EventType("player_state.updated") }
}
