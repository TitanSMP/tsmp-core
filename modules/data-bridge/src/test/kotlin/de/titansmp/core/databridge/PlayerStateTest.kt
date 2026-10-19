package de.titansmp.core.databridge

import de.titansmp.api.databridge.PlayerState
import de.titansmp.api.databridge.SerializedPlayerItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.time.Instant

class PlayerStateTest {
    @Test fun `effect duration decays across transfer and expires without negative duration`() {
        val captured = Instant.parse("2026-10-19T12:00:00Z")
        val finite = SerializedPotion("minecraft:speed", 100)
        assertEquals(80, finite.remainingTicksAt(captured, captured.plusMillis(1_000)))
        assertEquals(null, finite.remainingTicksAt(captured, captured.plusMillis(6_000)))
        assertEquals(-1, SerializedPotion("minecraft:speed", 0, infinite = true).remainingTicksAt(captured, captured.plusSeconds(3_600)))
    }

    @Test fun `state covers inventory armor offhand health food and complete experience`() {
        val state = PlayerState(
            UUID.randomUUID(), List(36) { if (it == 0) SerializedPlayerItem(byteArrayOf(1, 2)) else null },
            listOf(SerializedPlayerItem(byteArrayOf(3)), null, null, SerializedPlayerItem(byteArrayOf(4))),
            SerializedPlayerItem(byteArrayOf(5)), 18.5, 17, .42f, 37, 1498, 11,
        )
        assertEquals(36, state.inventory.size)
        assertEquals(4, state.armor.size)
        assertEquals(18.5, state.health)
        assertEquals(17, state.foodLevel)
        assertEquals(.42f, state.experience)
        assertEquals(37, state.level)
        assertEquals(1498, state.totalExperience)
        assertEquals(11, state.revision)
        assertFalse(PlayerState::class.java.declaredFields.any { it.name.contains("ender", ignoreCase = true) })
    }

    @Test fun `new player defaults can be represented as an empty 36 slot state`() {
        val state = PlayerState(UUID.randomUUID(), List(36) { null }, List(4) { null }, null, 20.0, 20, 0f, 0, 0)
        assertTrue(state.inventory.all { it == null })
        assertEquals(0f, state.experience)
    }

    @Test fun `revision tracker ignores duplicate and out of order events`() {
        val tracker = PlayerStateRevisionTracker()
        val uuid = UUID.randomUUID()
        assertTrue(tracker.observe(uuid, 10))
        assertTrue(tracker.observe(uuid, 11))
        assertFalse(tracker.observe(uuid, 10))
        assertFalse(tracker.observe(uuid, 11))
        assertTrue(tracker.observe(uuid, 12))
        tracker.recordLoaded(uuid, 20)
        assertFalse(tracker.observe(uuid, 19))
    }
}

private fun SerializedPotion(type: String, duration: Int, infinite: Boolean = false) =
    de.titansmp.api.databridge.PotionEffectData(type, duration, 0, false, true, true, infinite)
