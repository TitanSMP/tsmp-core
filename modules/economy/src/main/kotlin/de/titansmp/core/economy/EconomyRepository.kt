package de.titansmp.core.economy

import de.titansmp.api.economy.EconomyBalanceEntry
import de.titansmp.api.economy.EconomyReason
import de.titansmp.api.economy.Money
import java.time.Instant
import java.util.UUID

/** Repository-level mutation kind. The audit reason is tracked separately. */
enum class EconomyOperation { DEPOSIT, WITHDRAW, SET }

data class EconomyChangeRequest(
    val transactionId: UUID,
    val playerUuid: UUID,
    val operation: EconomyOperation,
    val amount: Money,
    val reason: EconomyReason,
    val actorUuid: UUID?,
    val createdAt: Instant,
    val sourceServerId: String,
)

data class EconomyLedgerEntry(
    val transactionId: UUID,
    val playerUuid: UUID,
    val counterpartyUuid: UUID?,
    val reason: EconomyReason,
    val amount: Money,
    val balanceBefore: Money,
    val balanceAfter: Money,
    val sourceServerId: String,
    val actorUuid: UUID?,
    val createdAt: Instant,
)

data class EconomyBalanceUpdate(
    val oldBalance: Money,
    val newBalance: Money,
    val entry: EconomyLedgerEntry,
)

data class EconomyTransferRequest(
    val transactionId: UUID,
    val senderUuid: UUID,
    val receiverUuid: UUID,
    val amount: Money,
    val reason: EconomyReason,
    val actorUuid: UUID?,
    val createdAt: Instant,
    val sourceServerId: String,
)

data class EconomyTransferUpdate(
    val transactionId: UUID,
    val sender: EconomyBalanceUpdate,
    val receiver: EconomyBalanceUpdate,
)

/**
 * Transactional balance storage. Implementations must atomically update the balance row and append
 * the corresponding audit row(s) in one database transaction.
 */
interface EconomyRepository {
    fun findBalance(playerUuid: UUID): Money

    fun topBalances(limit: Int): List<EconomyBalanceEntry>

    fun applyChange(request: EconomyChangeRequest): EconomyBalanceUpdate

    fun applyTransfer(request: EconomyTransferRequest): EconomyTransferUpdate
}

class EconomyInsufficientFundsException(message: String) : RuntimeException(message)
class EconomyNegativeBalanceException(message: String) : RuntimeException(message)
