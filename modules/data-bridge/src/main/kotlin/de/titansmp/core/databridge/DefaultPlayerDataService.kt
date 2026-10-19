package de.titansmp.core.databridge

import de.titansmp.api.databridge.PlayerDataService
import de.titansmp.api.databridge.PlayerState
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor

class DefaultPlayerDataService(private val repository: PlayerStateRepository, private val executor: Executor) : PlayerDataService {
    override fun load(playerUuid: UUID, serverId: String): CompletionStage<PlayerState?> =
        CompletableFuture.supplyAsync({ repository.load(playerUuid, serverId) }, executor)

    override fun save(state: PlayerState, serverId: String): CompletionStage<PlayerState> =
        CompletableFuture.supplyAsync({ repository.save(state, serverId) }, executor)
}
