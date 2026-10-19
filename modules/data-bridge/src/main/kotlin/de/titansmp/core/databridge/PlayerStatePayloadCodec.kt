package de.titansmp.core.databridge

import de.titansmp.api.databridge.PlayerState
import de.titansmp.api.databridge.PotionEffectData
import de.titansmp.api.databridge.AdvancementProgressData
import de.titansmp.api.databridge.CriterionProgressData
import de.titansmp.api.databridge.SerializedPlayerItem
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant
import java.util.UUID

/** Versioned binary representation of the full MariaDB player-state snapshot. */
internal object PlayerStatePayloadCodec {
    private val V1 = byteArrayOf(0x54, 0x53, 0x50, 0x53, 0, 0, 0, 1)
    private val V2 = byteArrayOf(0x54, 0x53, 0x50, 0x53, 0, 0, 0, 2)
    private val V3 = byteArrayOf(0x54, 0x53, 0x50, 0x53, 0, 0, 0, 3)
    private const val MAX_BYTES = 64 * 1024 * 1024
    private const val MAX_EFFECTS = 256
    private const val MAX_HIDDEN_DEPTH = 16
    private const val MAX_ADVANCEMENTS = 100_000
    private const val MAX_CRITERIA_PER_ADVANCEMENT = 4_096

    fun encode(state: PlayerState): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { out ->
            out.write(V3); out.writeInt(PlayerState.INVENTORY_SLOTS)
            state.inventory.forEach { out.writeItem(it) }
            out.writeInt(PlayerState.ARMOR_SLOTS); state.armor.forEach { out.writeItem(it) }
            out.writeItem(state.offhand)
            out.writeDouble(state.health); out.writeInt(state.foodLevel); out.writeFloat(state.experience)
            out.writeInt(state.level); out.writeInt(state.totalExperience)
            out.writeLong(state.capturedAt.toEpochMilli())
            require(state.potionEffects.size <= MAX_EFFECTS) { "Too many potion effects" }
            out.writeInt(state.potionEffects.size)
            state.potionEffects.forEach { out.writeEffect(it, 0) }
            require(state.advancements.size <= MAX_ADVANCEMENTS) { "Too many advancements" }
            out.writeInt(state.advancements.size)
            state.advancements.forEach { advancement ->
                out.writeUTF(advancement.advancementKey)
                require(advancement.criteria.size <= MAX_CRITERIA_PER_ADVANCEMENT) { "Too many advancement criteria" }
                out.writeInt(advancement.criteria.size)
                advancement.criteria.forEach { criterion ->
                    out.writeUTF(criterion.criterionKey)
                    out.writeBoolean(criterion.completed)
                }
            }
        }
    }.toByteArray().also { require(it.size <= MAX_BYTES) { "Player state exceeds payload limit" } }

    fun decode(uuid: UUID, bytes: ByteArray, revision: Long): PlayerState {
        require(bytes.size <= MAX_BYTES) { "Player state exceeds payload limit" }
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val magic = ByteArray(V1.size); input.readFully(magic)
            val version = when {
                magic.contentEquals(V1) -> 1
                magic.contentEquals(V2) -> 2
                magic.contentEquals(V3) -> 3
                else -> error("Unsupported player-state encoding")
            }
            require(input.readInt() == PlayerState.INVENTORY_SLOTS)
            val inventory = List(PlayerState.INVENTORY_SLOTS) { input.readItem() }
            require(input.readInt() == PlayerState.ARMOR_SLOTS)
            val armor = List(PlayerState.ARMOR_SLOTS) { input.readItem() }
            val offhand = input.readItem()
            val health = input.readDouble(); val food = input.readInt(); val experience = input.readFloat()
            val level = input.readInt(); val totalExperience = input.readInt()
            val capturedAt: Instant
            val effects: List<PotionEffectData>
            val advancements: List<AdvancementProgressData>
            if (version == 1) {
                // Legacy payloads had no effect section; leave their state unchanged and effects empty.
                capturedAt = Instant.EPOCH
                effects = emptyList()
                advancements = emptyList()
            } else {
                capturedAt = Instant.ofEpochMilli(input.readLong())
                val count = input.readInt()
                require(count in 0..MAX_EFFECTS) { "Invalid potion effect count" }
                effects = List(count) { input.readEffect(0) }
                if (version >= 3) {
                    val advancementCount = input.readInt()
                    require(advancementCount in 0..MAX_ADVANCEMENTS) { "Invalid advancement count" }
                    var totalCriteria = 0L
                    advancements = List(advancementCount) {
                        val key = input.readUTF()
                        val criterionCount = input.readInt()
                        require(criterionCount in 0..MAX_CRITERIA_PER_ADVANCEMENT) { "Invalid advancement criterion count" }
                        totalCriteria += criterionCount
                        require(totalCriteria <= 1_000_000L) { "Too many player advancement criteria" }
                        AdvancementProgressData(key, List(criterionCount) { CriterionProgressData(input.readUTF(), input.readBoolean()) })
                    }
                } else advancements = emptyList()
            }
            require(input.available() == 0) { "Trailing bytes in player-state payload" }
            return PlayerState(uuid, inventory, armor, offhand, health, food, experience, level, totalExperience, revision, effects, capturedAt, advancements)
        }
    }

    private fun DataOutputStream.writeItem(item: SerializedPlayerItem?) {
        val bytes = item?.payload
        require(bytes == null || bytes.size <= MAX_BYTES) { "Item payload exceeds limit" }
        writeInt(bytes?.size ?: -1); if (bytes != null) write(bytes)
    }

    private fun DataInputStream.readItem(): SerializedPlayerItem? {
        val size = readInt()
        require(size == -1 || size in 1..MAX_BYTES) { "Invalid item payload size" }
        return if (size == -1) null else SerializedPlayerItem(ByteArray(size).also(::readFully))
    }

    private fun DataOutputStream.writeEffect(effect: PotionEffectData, depth: Int) {
        require(depth <= MAX_HIDDEN_DEPTH) { "Potion effect hidden chain is too deep" }
        writeUTF(effect.type); writeInt(effect.durationTicks); writeInt(effect.amplifier)
        writeBoolean(effect.ambient); writeBoolean(effect.particles); writeBoolean(effect.icon); writeBoolean(effect.infinite)
        writeBoolean(effect.hiddenEffect != null)
        effect.hiddenEffect?.let { writeEffect(it, depth + 1) }
    }

    private fun DataInputStream.readEffect(depth: Int): PotionEffectData {
        require(depth <= MAX_HIDDEN_DEPTH) { "Potion effect hidden chain is too deep" }
        val type = readUTF(); val duration = readInt(); val amplifier = readInt()
        val ambient = readBoolean(); val particles = readBoolean(); val icon = readBoolean(); val infinite = readBoolean()
        val hidden = if (readBoolean()) readEffect(depth + 1) else null
        return PotionEffectData(type, duration, amplifier, ambient, particles, icon, infinite, hidden)
    }
}
