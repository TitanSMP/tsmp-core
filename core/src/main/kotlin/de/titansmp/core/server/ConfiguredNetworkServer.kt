package de.titansmp.core.server

import de.titansmp.api.server.NetworkServer
import de.titansmp.api.server.ServerIdentity

class ConfiguredNetworkServer(
    override val identity: ServerIdentity,
    override val networkName: String,
) : NetworkServer {
    init {
        require(networkName.isNotBlank()) { "Network name must not be blank" }
    }
}
