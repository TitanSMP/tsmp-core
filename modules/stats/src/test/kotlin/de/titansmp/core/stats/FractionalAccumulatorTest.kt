package de.titansmp.core.stats

import de.titansmp.api.stats.StatType
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class FractionalAccumulatorTest {
    private val accumulator = FractionalAccumulator()
    private val player = UUID.randomUUID()

    @Test
    fun `sub-unit values accumulate across flushes without loss`() {
        accumulator.record(player, StatType.BLOCKS_WALKED, 0.4)
        accumulator.record(player, StatType.BLOCKS_WALKED, 0.4)
        // First drain yields 0 whole blocks and keeps the 0.8 remainder.
        assertEquals(emptyMap(), accumulator.drain())
        accumulator.record(player, StatType.BLOCKS_WALKED, 0.4)
        // Now 1.2 whole blocks drain, leaving 0.2.
        assertEquals(mapOf(player to mapOf(StatType.BLOCKS_WALKED to 1L)), accumulator.drain())
        assertEquals(0.2, accumulator.pendingValue(player, StatType.BLOCKS_WALKED), 1e-9)
    }

    @Test
    fun `whole distances drain immediately`() {
        accumulator.record(player, StatType.BLOCKS_ELYTRA, 12.75)
        assertEquals(mapOf(player to mapOf(StatType.BLOCKS_ELYTRA to 12L)), accumulator.drain())
        assertEquals(0.75, accumulator.pendingValue(player, StatType.BLOCKS_ELYTRA), 1e-9)
    }

    @Test
    fun `non-positive amounts are ignored`() {
        accumulator.record(player, StatType.BLOCKS_WALKED, 0.0)
        accumulator.record(player, StatType.BLOCKS_WALKED, -1.0)
        assertEquals(emptyMap(), accumulator.drain())
    }
}
