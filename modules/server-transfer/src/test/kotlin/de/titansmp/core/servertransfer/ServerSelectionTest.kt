package de.titansmp.core.servertransfer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerSelectionTest {
    @Test
    fun `selects the least populated eligible backend`() {
        val selected = ServerSelection.selectLeastPopulated(
            "lobby",
            listOf(
                backend("alpha", "lobby", 72, 100),
                backend("beta", "lobby", 51, 100),
                backend("gamma", "lobby", 60, 100),
            ),
        )
        assertEquals("beta", selected?.name)
    }

    @Test
    fun `ties break deterministically by backend name`() {
        val selected = ServerSelection.selectLeastPopulated(
            "lobby",
            listOf(
                backend("zeta", "lobby", 51, 100),
                backend("alpha", "lobby", 51, 100),
                backend("mid", "lobby", 51, 100),
            ),
        )
        assertEquals("alpha", selected?.name)
    }

    @Test
    fun `full backends are excluded`() {
        val selected = ServerSelection.selectLeastPopulated(
            "lobby",
            listOf(
                backend("alpha", "lobby", 100, 100),
                backend("beta", "lobby", 100, 100),
            ),
        )
        assertNull(selected)
    }

    @Test
    fun `unavailable and non-accepting backends are excluded`() {
        val selected = ServerSelection.selectLeastPopulated(
            "lobby",
            listOf(
                backend("alpha", "lobby", 1, 100, available = false),
                backend("beta", "lobby", 2, 100, acceptingPlayers = false),
                backend("gamma", "lobby", 30, 100),
            ),
        )
        assertEquals("gamma", selected?.name)
    }

    @Test
    fun `server type filtering ignores other types`() {
        val selected = ServerSelection.selectLeastPopulated(
            "smp",
            listOf(
                backend("lobby-01", "lobby", 0, 100),
                backend("smp-01", "smp", 49, 50),
            ),
        )
        assertEquals("smp-01", selected?.name)
    }

    private fun backend(
        name: String,
        type: String,
        players: Int,
        max: Int,
        available: Boolean = true,
        acceptingPlayers: Boolean = true,
    ) = BackendSnapshot(name, type, players, max, available, acceptingPlayers)
}
