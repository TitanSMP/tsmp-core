package de.titansmp.shop

import de.titansmp.api.event.EventBus
import de.titansmp.api.event.EventMetadata
import de.titansmp.api.shop.ShopCategoryState
import de.titansmp.api.shop.ShopCategoryStateChanged
import de.titansmp.api.shop.ShopService
import de.titansmp.api.shop.ShopStateUpdate
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import java.util.logging.Level
import java.util.logging.Logger

class DefaultShopService(
    private val repository: ShopCategoryStateRepository,
    private val events: EventBus,
    private val executor: Executor,
    private val serverId: String,
    private val clock: Clock = Clock.systemUTC(),
    private val logger: Logger = Logger.getLogger(DefaultShopService::class.java.name),
) : ShopService {
    override fun loadStates(categoryIds: Set<String>): CompletionStage<Map<String, ShopCategoryState>> = CompletableFuture.supplyAsync({ repository.load(categoryIds) }, executor)
    override fun isEnabled(categoryId: String): CompletionStage<Boolean> = loadStates(setOf(categoryId)).thenApply { it.getValue(categoryId).enabled }
    override fun setEnabled(categoryId: String, enabled: Boolean, updatedBy: UUID): CompletionStage<ShopStateUpdate> = CompletableFuture.supplyAsync({ repository.set(categoryId, enabled, updatedBy, serverId, clock.instant()) }, executor).thenCompose { state ->
        events.publish(ShopCategoryStateChanged(EventMetadata(eventType=ShopCategoryStateChanged.TYPE, timestamp=state.updatedAt, sourceServerId=serverId),categoryId,enabled,updatedBy,state.updatedAt))
            .handle { _, failure ->
                if (failure != null) logger.log(Level.WARNING, "Shop category $categoryId was saved to MariaDB but Redis publication failed; DB refresh will reconcile other servers", failure)
                ShopStateUpdate(state, failure == null)
            }
    }
}
