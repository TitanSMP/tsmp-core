package de.titansmp.api.databridge

import java.util.UUID
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
) {
    init {
        require(inventory.size == INVENTORY_SLOTS) { "Main inventory must contain $INVENTORY_SLOTS slots" }
        require(armor.size == ARMOR_SLOTS) { "Armor must contain $ARMOR_SLOTS slots" }
        require(health.isFinite() && health >= 0.0) { "Health must be finite and non-negative" }
        require(foodLevel in 0..20) { "Food level must be between 0 and 20" }
        require(experience.isFinite() && experience in 0.0f..1.0f) { "Experience progress must be between 0 and 1" }
        require(level >= 0 && totalExperience >= 0) { "Experience values cannot be negative" }
        require(revision >= 0) { "Revision cannot be negative" }
    }

    fun withRevision(value: Long): PlayerState = copy(revision = value)

    companion object {
        const val INVENTORY_SLOTS = 36
        const val ARMOR_SLOTS = 4
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
