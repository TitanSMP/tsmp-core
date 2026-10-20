package de.titansmp.api.economy

import de.titansmp.api.event.EventMetadata
import de.titansmp.api.event.EventType
import de.titansmp.api.event.NetworkEvent
import java.util.UUID

/**
 * Published after a balance change committed to MariaDB.
 *
 * A transfer publishes one event per affected account with the same [transactionId]. The event is
 * propagation only; consumers must tolerate duplicates and re-read MariaDB when correctness matters.
 */
data class EconomyBalanceChanged(
    override val metadata: EventMetadata,
    val transactionId: UUID,
    val playerUuid: UUID,
    val amount: Money,
    val oldBalance: Money,
    val newBalance: Money,
    val reason: EconomyReason,
    val counterpartyUuid: UUID?,
    val counterpartyName: String?,
    val actorUuid: UUID?,
    val sourceServerId: String,
) : NetworkEvent {
    companion object {
        val TYPE = EventType("economy.balance_changed")
    }
}
