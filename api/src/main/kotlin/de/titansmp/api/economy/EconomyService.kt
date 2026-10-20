package de.titansmp.api.economy

import java.util.UUID
import java.util.concurrent.CompletionStage

/** Result of a single balance mutation. */
data class EconomyChange(
    val transactionId: UUID,
    val playerUuid: UUID,
    val amount: Money,
    val oldBalance: Money,
    val newBalance: Money,
    val reason: EconomyReason,
    val actorUuid: UUID?,
    val counterpartyUuid: UUID?,
    val sourceServerId: String,
)

/** Both sides of an atomic player-to-player transfer sharing one transaction id. */
data class EconomyTransfer(
    val transactionId: UUID,
    val sender: EconomyChange,
    val receiver: EconomyChange,
)

/** One row of the public balance leaderboard. */
data class EconomyBalanceEntry(
    val rank: Int,
    val playerUuid: UUID,
    val username: String,
    val balance: Money,
)

/**
 * The single business boundary for TitanCoins.
 *
 * All implementations must persist through the shared MariaDB DataSource. Redis only propagates
 * [EconomyBalanceChanged] events after the database transaction committed. A null `requester` or
 * `actor` represents a trusted console/system caller; player callers are authorized through the
 * platform permission adapter, never through rank names.
 *
 * Permission-free mutation methods ([deposit], [withdraw], [setBalance], [transfer]) are the
 * extension points for future features such as an auction house or admin shop. Those callers must
 * supply a specific [EconomyReason] and are responsible for their own authorization rules.
 */
interface EconomyService {
    /** Reads the persisted balance; a player without a balance row has [Money.ZERO]. */
    fun getBalance(player: UUID): CompletionStage<Money>

    /** Reads a balance after checking `titansmp.economy.balance` or `.balance.others`. */
    fun getBalanceFor(requester: UUID?, target: UUID): CompletionStage<Money>

    /** Returns the richest players using a database-side `ORDER BY balance DESC LIMIT`. */
    fun topBalances(requester: UUID?, limit: Int = DEFAULT_TOP_LIMIT): CompletionStage<List<EconomyBalanceEntry>>

    /** Player-facing transfer: checks permission, self-transfer, amount, and network presence. */
    fun pay(sender: UUID, receiver: UUID, amount: Money, serverId: String): CompletionStage<EconomyTransfer>

    fun adminGive(target: UUID, amount: Money, actor: UUID?, serverId: String): CompletionStage<EconomyChange>
    fun adminTake(target: UUID, amount: Money, actor: UUID?, serverId: String): CompletionStage<EconomyChange>
    fun adminSet(target: UUID, amount: Money, actor: UUID?, serverId: String): CompletionStage<EconomyChange>

    fun deposit(player: UUID, amount: Money, reason: EconomyReason, actor: UUID?, serverId: String): CompletionStage<EconomyChange>
    fun withdraw(player: UUID, amount: Money, reason: EconomyReason, actor: UUID?, serverId: String): CompletionStage<EconomyChange>
    fun setBalance(player: UUID, amount: Money, reason: EconomyReason, actor: UUID?, serverId: String): CompletionStage<EconomyChange>
    fun transfer(sender: UUID, receiver: UUID, amount: Money, reason: EconomyReason, actor: UUID?, serverId: String): CompletionStage<EconomyTransfer>

    /** Executes or replays a network-internal transfer under a stable transaction ID. */
    fun transferOnce(transactionId: UUID, sender: UUID, receiver: UUID, amount: Money, reason: EconomyReason, actor: UUID?, serverId: String): CompletionStage<EconomyTransfer> =
        transfer(sender, receiver, amount, reason, actor, serverId)

    companion object {
        const val DEFAULT_TOP_LIMIT = 10
        const val MAX_TOP_LIMIT = 50
    }
}

/** Platform adapter delegates permission semantics (including wildcards) to LuckPerms. */
fun interface EconomyPermissionService {
    fun hasPermission(playerId: UUID, permission: String): CompletionStage<Boolean>
}

/** Stable permission nodes. LuckPerms decides who holds them. */
object EconomyPermissions {
    const val BALANCE = "titansmp.economy.balance"
    const val BALANCE_OTHERS = "titansmp.economy.balance.others"
    const val PAY = "titansmp.economy.pay"
    const val BALANCETOP = "titansmp.economy.balancetop"
    const val ADMIN = "titansmp.economy.admin"
}

class EconomyException(val code: Code, message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    enum class Code {
        NO_PERMISSION,
        INVALID_AMOUNT,
        OVERFLOW,
        NEGATIVE_BALANCE,
        INSUFFICIENT_FUNDS,
        SELF_TRANSFER,
        TARGET_NOT_ONLINE,
        PLAYER_NOT_FOUND,
        LOOKUP_FAILED,
        PERMISSION_CHECK_FAILED,
        DATABASE_FAILURE,
    }
}
