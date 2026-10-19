package de.titansmp.core.databridge

import de.titansmp.api.databridge.PlayerTransferState
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlayerTransferRegistryTest {
    @Test fun `ready transfer advances once and completes at or above saved revision`() {
        val registry = PlayerTransferRegistry()
        val player = UUID.randomUUID()
        val transfer = assertNotNull(registry.begin(player, "smp-01", "smp-02"))
        assertEquals(PlayerTransferState.PREPARING, transfer.state)
        assertNotNull(registry.ready(transfer.transferId, player, "smp-01", "smp-02", 42))
        assertNull(registry.ready(transfer.transferId, player, "smp-01", "smp-02", 42))
        assertNotNull(registry.connecting(transfer.transferId))
        assertFalse(registry.complete(transfer.transferId, player, 41))
        assertTrue(registry.complete(transfer.transferId, player, 43))
        assertEquals(PlayerTransferState.COMPLETED, registry.find(transfer.transferId)?.state)
        assertNull(registry.findInProgress(player))
    }

    @Test fun `failed or timed out transfer releases player and old ids cannot alter a new transfer`() {
        val registry = PlayerTransferRegistry()
        val player = UUID.randomUUID()
        val old = assertNotNull(registry.begin(player, "smp-01", "smp-02"))
        assertTrue(registry.fail(old.transferId, player))
        assertNull(registry.ready(old.transferId, player, "smp-01", "smp-02", 9))
        val current = assertNotNull(registry.begin(player, "smp-01", "smp-03"))
        assertFalse(registry.fail(old.transferId, player))
        assertEquals(current.transferId, registry.findInProgress(player)?.transferId)
        assertNull(registry.ready(old.transferId, player, "smp-01", "smp-03", 10))
    }

    @Test fun `only one transfer can be active per player`() {
        val registry = PlayerTransferRegistry()
        val player = UUID.randomUUID()
        assertNotNull(registry.begin(player, "smp-01", "smp-02"))
        assertNull(registry.begin(player, "smp-01", "smp-03"))
    }
}
