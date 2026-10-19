package de.titansmp.core.enderchest

import de.titansmp.api.enderchest.EnderChestStorage
import de.titansmp.api.enderchest.EnderChestService
import de.titansmp.api.enderchest.EnderChestPermissionCheck
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor

interface EnderChestRepository {
    fun load(playerId: UUID, serverId: String): EnderChestStorage
    fun save(storage: EnderChestStorage, serverId: String): EnderChestStorage
}

/** Runs synchronous JDBC operations on the feature's worker executor, never on a Paper thread. */
class DefaultEnderChestService(
    private val repository: EnderChestRepository,
    private val executor: Executor,
    private val maximumRows: Int = 2,
) : EnderChestService {
    init {
        require(maximumRows in 1..(EnderChestStorage.CAPACITY / 27))
    }

    override fun load(playerId: UUID, serverId: String): CompletionStage<EnderChestStorage> =
        CompletableFuture.supplyAsync({ repository.load(playerId, serverId) }, executor)

    override fun save(storage: EnderChestStorage, serverId: String): CompletionStage<EnderChestStorage> =
        CompletableFuture.supplyAsync({ repository.save(storage, serverId) }, executor)

    override fun unlockedRows(permissionCheck: EnderChestPermissionCheck): Int =
        (maximumRows downTo 1).firstOrNull { row -> permissionCheck.hasPermission(ROWS_PERMISSION_PREFIX + row) } ?: 1

    private companion object {
        const val ROWS_PERMISSION_PREFIX = "titansmp.enderchest.rows."
    }
}
