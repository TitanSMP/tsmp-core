package de.titansmp.core.databridge

import de.titansmp.api.databridge.PlayerState
import java.util.UUID

interface PlayerStateRepository {
    fun load(playerUuid: UUID, serverId: String): PlayerState?
    fun save(state: PlayerState, serverId: String): PlayerState
}
