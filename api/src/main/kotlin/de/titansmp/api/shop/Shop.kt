package de.titansmp.api.shop

import de.titansmp.api.event.EventMetadata
import de.titansmp.api.event.EventType
import de.titansmp.api.event.NetworkEvent
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletionStage

data class ShopCategoryState(val categoryId: String, val enabled: Boolean, val updatedBy: UUID?, val updatedAt: Instant)
data class ShopCategoryStateChanged(
    override val metadata: EventMetadata,
    val categoryId: String,
    val enabled: Boolean,
    val updatedBy: UUID,
    val updatedAt: Instant,
) : NetworkEvent {
    companion object { val TYPE = EventType("shop.category_state_changed"); const val VERSION = 1 }
}

data class ShopStateUpdate(val state: ShopCategoryState, val broadcasted: Boolean)
interface ShopService {
    fun loadStates(categoryIds: Set<String>): CompletionStage<Map<String, ShopCategoryState>>
    fun isEnabled(categoryId: String): CompletionStage<Boolean>
    fun setEnabled(categoryId: String, enabled: Boolean, updatedBy: UUID): CompletionStage<ShopStateUpdate>
}
