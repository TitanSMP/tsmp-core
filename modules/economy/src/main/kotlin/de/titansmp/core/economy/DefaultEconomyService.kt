package de.titansmp.core.economy

import de.titansmp.api.economy.EconomyBalanceChanged
import de.titansmp.api.economy.EconomyBalanceEntry
import de.titansmp.api.economy.EconomyChange
import de.titansmp.api.economy.EconomyException
import de.titansmp.api.economy.EconomyPermissionService
import de.titansmp.api.economy.EconomyPermissions
import de.titansmp.api.economy.EconomyReason
import de.titansmp.api.economy.EconomyService
import de.titansmp.api.economy.EconomyTransfer
import de.titansmp.api.economy.Money
import de.titansmp.api.economy.MoneyOverflowException
import de.titansmp.api.event.EventBus
import de.titansmp.api.event.EventMetadata
import de.titansmp.api.player.NetworkPlayer
import de.titansmp.api.player.PlayerService
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Shared economy business logic used by Paper commands and future network features.
 *
 * MariaDB is the source of truth: the repository transaction commits first, then a Redis event is
 * published. A publish failure is logged and never rolls back or rewrites the committed balance.
 */
class DefaultEconomyService(
    private val repository: EconomyRepository,
    private val permissions: EconomyPermissionService,
    private val players: PlayerService,
    private val eventBus: EventBus,
    private val executor: Executor,
    private val clock: Clock = Clock.systemUTC(),
    private val logger: Logger = Logger.getLogger(DefaultEconomyService::class.java.name),
) : EconomyService {
    override fun getBalance(player: UUID): CompletionStage<Money> = db { repository.findBalance(player) }

    override fun getBalanceFor(requester: UUID?, target: UUID): CompletionStage<Money> {
        val permission = if (requester != null && requester != target) EconomyPermissions.BALANCE_OTHERS else EconomyPermissions.BALANCE
        return authorize(requester, permission).thenCompose { getBalance(target) }
    }

    override fun topBalances(requester: UUID?, limit: Int): CompletionStage<List<EconomyBalanceEntry>> =
        authorize(requester, EconomyPermissions.BALANCETOP).thenCompose {
            val safeLimit = limit.coerceIn(1, EconomyService.MAX_TOP_LIMIT)
            db { repository.topBalances(safeLimit) }
        }

    override fun pay(sender: UUID, receiver: UUID, amount: Money, serverId: String): CompletionStage<EconomyTransfer> =
        authorize(sender, EconomyPermissions.PAY)
            .thenCompose { requirePositive(amount) }
            .thenCompose { if (sender == receiver) failed(selfTransfer()) else lookupPair(sender, receiver) }
            .thenCompose { pair ->
                val senderPlayer = pair.first
                val receiverPlayer = pair.second
                when {
                    receiverPlayer == null -> failed(EconomyException(EconomyException.Code.PLAYER_NOT_FOUND, "That player is not known to the TitanSMP network."))
                    !receiverPlayer.online -> failed(EconomyException(EconomyException.Code.TARGET_NOT_ONLINE, "That player is not online."))
                    else -> persistTransfer(
                        sender = sender,
                        receiver = receiver,
                        amount = amount,
                        reason = EconomyReason.PAYMENT,
                        actor = null,
                        serverId = serverId,
                        senderName = senderPlayer?.username,
                        receiverName = receiverPlayer.username,
                    )
                }
            }

    override fun adminGive(target: UUID, amount: Money, actor: UUID?, serverId: String): CompletionStage<EconomyChange> =
        authorize(actor, EconomyPermissions.ADMIN).thenCompose {
            deposit(target, amount, EconomyReason.ADMIN_GIVE, actor, serverId)
        }

    override fun adminTake(target: UUID, amount: Money, actor: UUID?, serverId: String): CompletionStage<EconomyChange> =
        authorize(actor, EconomyPermissions.ADMIN).thenCompose {
            withdraw(target, amount, EconomyReason.ADMIN_TAKE, actor, serverId)
        }

    override fun adminSet(target: UUID, amount: Money, actor: UUID?, serverId: String): CompletionStage<EconomyChange> =
        authorize(actor, EconomyPermissions.ADMIN).thenCompose {
            setBalance(target, amount, EconomyReason.ADMIN_SET, actor, serverId)
        }

    override fun deposit(player: UUID, amount: Money, reason: EconomyReason, actor: UUID?, serverId: String): CompletionStage<EconomyChange> =
        requirePositive(amount).thenCompose {
            persistChange(player, amount, EconomyOperation.DEPOSIT, reason, actor, serverId)
        }

    override fun withdraw(player: UUID, amount: Money, reason: EconomyReason, actor: UUID?, serverId: String): CompletionStage<EconomyChange> =
        requirePositive(amount).thenCompose {
            persistChange(player, amount, EconomyOperation.WITHDRAW, reason, actor, serverId)
        }

    override fun setBalance(player: UUID, amount: Money, reason: EconomyReason, actor: UUID?, serverId: String): CompletionStage<EconomyChange> =
        requireNonNegative(amount).thenCompose {
            persistChange(player, amount, EconomyOperation.SET, reason, actor, serverId)
        }

    override fun transfer(
        sender: UUID,
        receiver: UUID,
        amount: Money,
        reason: EconomyReason,
        actor: UUID?,
        serverId: String,
    ): CompletionStage<EconomyTransfer> =
        requirePositive(amount)
            .thenCompose { if (sender == receiver) failed(selfTransfer()) else lookupPair(sender, receiver) }
            .thenCompose { pair ->
                when {
                    pair.first == null -> failed(EconomyException(EconomyException.Code.PLAYER_NOT_FOUND, "The sender is not known to the TitanSMP network."))
                    pair.second == null -> failed(EconomyException(EconomyException.Code.PLAYER_NOT_FOUND, "The receiver is not known to the TitanSMP network."))
                    else -> persistTransfer(sender, receiver, amount, reason, actor, serverId, pair.first?.username, pair.second?.username)
                }
            }

    private fun persistChange(
        player: UUID,
        amount: Money,
        operation: EconomyOperation,
        reason: EconomyReason,
        actor: UUID?,
        serverId: String,
    ): CompletionStage<EconomyChange> {
        val transactionId = UUID.randomUUID()
        val createdAt = clock.instant()
        return db {
            repository.applyChange(
                EconomyChangeRequest(transactionId, player, operation, amount, reason, actor, createdAt, serverId),
            )
        }.thenCompose { update ->
            val change = EconomyChange(
                transactionId = transactionId,
                playerUuid = player,
                amount = amount,
                oldBalance = update.oldBalance,
                newBalance = update.newBalance,
                reason = reason,
                actorUuid = actor,
                counterpartyUuid = null,
                sourceServerId = serverId,
            )
            publish(change, counterpartyName = null).thenApply { change }
        }
    }

    private fun persistTransfer(
        sender: UUID,
        receiver: UUID,
        amount: Money,
        reason: EconomyReason,
        actor: UUID?,
        serverId: String,
        senderName: String?,
        receiverName: String?,
    ): CompletionStage<EconomyTransfer> {
        val transactionId = UUID.randomUUID()
        val createdAt = clock.instant()
        return db {
            repository.applyTransfer(
                EconomyTransferRequest(transactionId, sender, receiver, amount, reason, actor, createdAt, serverId),
            )
        }.thenCompose { update ->
            val senderChange = EconomyChange(
                transactionId = transactionId,
                playerUuid = sender,
                amount = amount,
                oldBalance = update.sender.oldBalance,
                newBalance = update.sender.newBalance,
                reason = reason,
                actorUuid = actor,
                counterpartyUuid = receiver,
                sourceServerId = serverId,
            )
            val receiverChange = EconomyChange(
                transactionId = transactionId,
                playerUuid = receiver,
                amount = amount,
                oldBalance = update.receiver.oldBalance,
                newBalance = update.receiver.newBalance,
                reason = reason,
                actorUuid = actor,
                counterpartyUuid = sender,
                sourceServerId = serverId,
            )
            val senderPublished = publish(senderChange, receiverName)
            val receiverPublished = publish(receiverChange, senderName)
            senderPublished.thenCombine(receiverPublished) { _, _ ->
                EconomyTransfer(transactionId, senderChange, receiverChange)
            }
        }
    }

    private fun publish(change: EconomyChange, counterpartyName: String?): CompletionStage<Unit> {
        val event = EconomyBalanceChanged(
            metadata = EventMetadata(
                eventType = EconomyBalanceChanged.TYPE,
                timestamp = clock.instant(),
                sourceServerId = change.sourceServerId,
            ),
            transactionId = change.transactionId,
            playerUuid = change.playerUuid,
            amount = change.amount,
            oldBalance = change.oldBalance,
            newBalance = change.newBalance,
            reason = change.reason,
            counterpartyUuid = change.counterpartyUuid,
            counterpartyName = counterpartyName,
            actorUuid = change.actorUuid,
            sourceServerId = change.sourceServerId,
        )
        return try {
            eventBus.publish(event).handle { _, failure ->
                if (failure != null) {
                    logger.log(
                        Level.SEVERE,
                        "Economy change ${change.transactionId} was committed but Redis event ${EconomyBalanceChanged.TYPE.value} could not be published",
                        failure,
                    )
                }
                Unit
            }
        } catch (failure: Throwable) {
            logger.log(
                Level.SEVERE,
                "Economy change ${change.transactionId} was committed but Redis event ${EconomyBalanceChanged.TYPE.value} could not be published",
                failure,
            )
            CompletableFuture.completedFuture(Unit)
        }
    }

    private fun authorize(actor: UUID?, permission: String): CompletionStage<Unit> {
        if (actor == null) return CompletableFuture.completedFuture(Unit)
        val checked = try {
            permissions.hasPermission(actor, permission)
        } catch (failure: Throwable) {
            CompletableFuture.failedFuture<Boolean>(failure)
        }
        return checked.thenCompose { allowed ->
            if (allowed) CompletableFuture.completedFuture(Unit)
            else failed(EconomyException(EconomyException.Code.NO_PERMISSION, "You do not have permission to do that."))
        }.exceptionallyCompose { failure ->
            val cause = unwrap(failure)
            failed(
                if (cause is EconomyException) cause
                else EconomyException(EconomyException.Code.PERMISSION_CHECK_FAILED, "Your permissions could not be verified.", cause),
            )
        }
    }

    private fun requirePositive(amount: Money): CompletionStage<Unit> =
        if (amount.isPositive()) CompletableFuture.completedFuture(Unit)
        else failed(EconomyException(EconomyException.Code.INVALID_AMOUNT, "The amount must be greater than zero."))

    private fun requireNonNegative(amount: Money): CompletionStage<Unit> =
        if (amount.isNonNegative()) CompletableFuture.completedFuture(Unit)
        else failed(EconomyException(EconomyException.Code.INVALID_AMOUNT, "The amount cannot be negative."))

    private fun lookupPair(first: UUID, second: UUID): CompletionStage<Pair<NetworkPlayer?, NetworkPlayer?>> =
        CompletableFuture.supplyAsync({ players.findPlayer(first) to players.findPlayer(second) }, executor)
            .exceptionallyCompose { failure ->
                failed(EconomyException(EconomyException.Code.LOOKUP_FAILED, "Player data is unavailable right now.", unwrap(failure)))
            }

    private fun <T> db(block: () -> T): CompletionStage<T> =
        CompletableFuture.supplyAsync(block, executor).exceptionallyCompose { failure -> failed(databaseFailure(failure)) }

    private fun databaseFailure(failure: Throwable): EconomyException {
        val cause = unwrap(failure)
        return when (cause) {
            is EconomyException -> cause
            is EconomyInsufficientFundsException -> EconomyException(
                EconomyException.Code.INSUFFICIENT_FUNDS,
                cause.message ?: "That player does not have enough TitanCoins.",
                cause,
            )
            is EconomyNegativeBalanceException -> EconomyException(
                EconomyException.Code.NEGATIVE_BALANCE,
                cause.message ?: "A balance can never be negative.",
                cause,
            )
            is MoneyOverflowException -> EconomyException(
                EconomyException.Code.OVERFLOW,
                "That amount is too large for the economy.",
                cause,
            )
            else -> EconomyException(EconomyException.Code.DATABASE_FAILURE, "Economy data is unavailable right now.", cause)
        }
    }

    private fun selfTransfer() = EconomyException(EconomyException.Code.SELF_TRANSFER, "You cannot send money to yourself.")

    private fun unwrap(failure: Throwable): Throwable {
        var current = failure
        while ((current is CompletionException || current is ExecutionException) && current.cause != null) {
            current = current.cause!!
        }
        return current
    }

    private fun <T> failed(failure: Throwable): CompletionStage<T> = CompletableFuture.failedFuture(failure)
}
