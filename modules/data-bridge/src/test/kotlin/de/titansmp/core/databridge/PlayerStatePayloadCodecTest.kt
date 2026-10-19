package de.titansmp.core.databridge

import de.titansmp.api.databridge.PlayerState
import de.titansmp.api.databridge.PotionEffectData
import de.titansmp.api.databridge.AdvancementProgressData
import de.titansmp.api.databridge.CriterionProgressData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.Instant
import java.util.UUID

class PlayerStatePayloadCodecTest {
    @Test fun `round trips complete potion metadata and nested hidden effect`() {
        val capturedAt = Instant.parse("2026-10-19T12:00:00Z")
        val effects = listOf(
            PotionEffectData("minecraft:speed", 2400, 1, false, true, true),
            PotionEffectData("minecraft:strength", 600, 2, true, false, false),
            PotionEffectData("minecraft:regeneration", 80, 0, false, true, true, hiddenEffect =
                PotionEffectData("minecraft:regeneration", 40, 1, false, false, true)),
            PotionEffectData("minecraft:night_vision", 0, 0, false, true, true, infinite = true),
        )
        val advancementState = listOf(
            AdvancementProgressData("minecraft:story/mine_stone", listOf(CriterionProgressData("stone", true))),
            AdvancementProgressData("titansmp:test", listOf(CriterionProgressData("criterion_a", true), CriterionProgressData("criterion_b", false), CriterionProgressData("criterion_c", false))),
            AdvancementProgressData("someplugin:multi", listOf(CriterionProgressData("one", false), CriterionProgressData("two", true))),
        )
        val state = PlayerState(UUID.randomUUID(), List(36) { null }, List(4) { null }, null, 20.0, 20, .5f, 4, 100, 13, effects, capturedAt, advancementState)

        val decoded = PlayerStatePayloadCodec.decode(state.playerUuid, PlayerStatePayloadCodec.encode(state), state.revision)

        assertEquals(state, decoded)
        assertEquals(effects, decoded.potionEffects)
        assertEquals(advancementState, decoded.advancements)
    }

    @Test fun `legacy version one payload loads with empty effect list`() {
        val uuid = UUID.randomUUID()
        val payload = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
            out.write(byteArrayOf(0x54, 0x53, 0x50, 0x53, 0, 0, 0, 1))
            out.writeInt(36); repeat(36) { out.writeInt(-1) }
            out.writeInt(4); repeat(4) { out.writeInt(-1) }
            out.writeInt(-1); out.writeDouble(19.0); out.writeInt(19); out.writeFloat(.25f); out.writeInt(3); out.writeInt(50)
        } }.toByteArray()

        val decoded = PlayerStatePayloadCodec.decode(uuid, payload, 8)

        assertTrue(decoded.potionEffects.isEmpty())
        assertEquals(19.0, decoded.health)
        assertEquals(8, decoded.revision)
        assertTrue(decoded.advancements.isEmpty())
    }

    @Test fun `legacy version two payload retains effects but has empty advancements`() {
        val uuid = UUID.randomUUID()
        val payload = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
            out.write(byteArrayOf(0x54, 0x53, 0x50, 0x53, 0, 0, 0, 2))
            out.writeInt(36); repeat(36) { out.writeInt(-1) }
            out.writeInt(4); repeat(4) { out.writeInt(-1) }
            out.writeInt(-1); out.writeDouble(20.0); out.writeInt(20); out.writeFloat(0f); out.writeInt(0); out.writeInt(0)
            out.writeLong(Instant.parse("2026-10-19T12:00:00Z").toEpochMilli()); out.writeInt(0)
        } }.toByteArray()

        val decoded = PlayerStatePayloadCodec.decode(uuid, payload, 9)

        assertTrue(decoded.potionEffects.isEmpty())
        assertTrue(decoded.advancements.isEmpty())
    }
}
