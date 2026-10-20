package de.titansmp.core.servertransfer

import de.titansmp.core.config.ServerTransferBackendConfig
import de.titansmp.core.config.ServerTransferConfig
import de.titansmp.core.config.ServerTransferTypeConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ServerTransferConfigTest {
    @Test
    fun `unknown server types are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ServerTransferConfig(
                enabled = true,
                types = mapOf("lobby" to ServerTransferTypeConfig(maxPlayers = 100)),
                backends = mapOf("alpha" to ServerTransferBackendConfig("missing")),
            )
        }
    }

    @Test
    fun `disabled server types are not joinable`() {
        val config = ServerTransferConfig(
            enabled = true,
            types = mapOf(
                "lobby" to ServerTransferTypeConfig(enabled = true, maxPlayers = 100),
                "smp" to ServerTransferTypeConfig(enabled = false, maxPlayers = 50),
            ),
        )
        assertEquals(listOf("lobby"), config.enabledTypes())
        assertNull(config.typeSettings("smp"))
    }

    @Test
    fun `backend mapping is configuration driven and case sensitive by name`() {
        val config = ServerTransferConfig(
            enabled = true,
            types = mapOf("lobby" to ServerTransferTypeConfig(maxPlayers = 100)),
            backends = mapOf("lobby-01" to ServerTransferBackendConfig("lobby")),
        )
        assertEquals("lobby", config.typeForBackend("lobby-01"))
        assertNull(config.typeForBackend("lobby-02"))
    }

    @Test
    fun `invalid capacities priorities and aliases fail fast`() {
        assertFailsWith<IllegalArgumentException> {
            ServerTransferConfig(types = mapOf("lobby" to ServerTransferTypeConfig(maxPlayers = 0)))
        }
        assertFailsWith<IllegalArgumentException> {
            ServerTransferConfig(defaultPriority = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            ServerTransferConfig(
                types = mapOf("lobby" to ServerTransferTypeConfig(maxPlayers = 100)),
                aliases = mapOf("hub" to "missing"),
            )
        }
    }

    @Test
    fun `aliases resolve to configured types only`() {
        val config = ServerTransferConfig(
            types = mapOf("lobby" to ServerTransferTypeConfig(maxPlayers = 100)),
            aliases = mapOf("hub" to "lobby"),
        )
        assertEquals("lobby", config.aliasTarget("hub"))
        assertEquals("lobby", config.aliasTarget("HUB"))
        assertNull(config.aliasTarget("lobby"))
    }

    @Test
    fun `enabled configuration without types fails`() {
        assertFailsWith<IllegalArgumentException> { ServerTransferConfig(enabled = true) }
    }

    @Test
    fun `backend names are not hardcoded in the production command surface`() {
        assertEquals(setOf("join", "lobby", "hub"), ServerTransferCommandSurface.COMMAND_NAMES)
        assertEquals(false, ServerTransferCommandSurface.COMMAND_NAMES.contains("server"))
    }
}
