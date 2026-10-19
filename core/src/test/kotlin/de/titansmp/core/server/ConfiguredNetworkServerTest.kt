package de.titansmp.core.server

import de.titansmp.api.server.ServerEnvironment
import de.titansmp.api.server.ServerIdentity
import de.titansmp.api.server.ServerType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfiguredNetworkServerTest {
    @Test
    fun `preserves configured server identity and network name`() {
        val identity = ServerIdentity("smp-01", ServerType.SMP, ServerEnvironment.PRODUCTION)

        val server = ConfiguredNetworkServer(identity, "TitanSMP")

        assertEquals(identity, server.identity)
        assertEquals("smp-01", server.identity.id)
        assertEquals(ServerType.SMP, server.identity.type)
        assertEquals(ServerEnvironment.PRODUCTION, server.identity.environment)
        assertEquals("TitanSMP", server.networkName)
    }

    @Test
    fun `requires an explicit non blank server id`() {
        assertFailsWith<IllegalArgumentException> {
            ServerIdentity(" ", ServerType.LOBBY, ServerEnvironment.DEVELOPMENT)
        }
    }
}
