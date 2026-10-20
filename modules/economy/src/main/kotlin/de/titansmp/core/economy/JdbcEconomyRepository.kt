package de.titansmp.core.economy

import de.titansmp.api.economy.EconomyBalanceEntry
import de.titansmp.api.economy.EconomyReason
import de.titansmp.api.economy.Money
import java.nio.ByteBuffer
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/**
 * Transactional JDBC adapter over the shared TitanSMP DataSource.
 *
 * Every mutation locks the affected balance rows with `SELECT ... FOR UPDATE` before validating and
 * writing. Transfers lock both rows in ascending UUID order, which gives concurrent transfers a
 * deterministic lock order and prevents lost updates. The balance update and all audit rows are
 * committed together; a failed validation rolls the whole transaction back.
 */
class JdbcEconomyRepository(private val dataSource: DataSource) : EconomyRepository {
    override fun findBalance(playerUuid: UUID): Money = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT balance FROM economy_balances WHERE player_uuid = ?").use { statement ->
            statement.setBytes(1, playerUuid.toBytes())
            statement.executeQuery().use { result -> if (result.next()) result.money("balance") else Money.ZERO }
        }
    }

    override fun topBalances(limit: Int): List<EconomyBalanceEntry> {
        require(limit in 1..MAX_TOP_LIMIT) { "Top limit must be between 1 and $MAX_TOP_LIMIT" }
        return dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT b.player_uuid, b.balance, p.username FROM economy_balances b " +
                    "JOIN players p ON p.uuid = b.player_uuid WHERE b.balance > 0 " +
                    "ORDER BY b.balance DESC, b.player_uuid ASC LIMIT ?",
            ).use { statement ->
                statement.setInt(1, limit)
                statement.executeQuery().use { result ->
                    buildList {
                        var rank = 1
                        while (result.next()) {
                            add(
                                EconomyBalanceEntry(
                                    rank = rank++,
                                    playerUuid = result.getBytes("player_uuid").toUuid(),
                                    username = result.getString("username"),
                                    balance = result.money("balance"),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    override fun applyChange(request: EconomyChangeRequest): EconomyBalanceUpdate = transaction { connection ->
        ensureBalanceRow(connection, request.playerUuid, request.createdAt)
        val before = lockBalance(connection, request.playerUuid)
        val after = when (request.operation) {
            EconomyOperation.DEPOSIT -> before.plus(request.amount)
            EconomyOperation.WITHDRAW -> withdraw(before, request.amount, "That player does not have enough TitanCoins for this withdrawal.")
            EconomyOperation.SET -> request.amount
        }
        validateBalance(after)
        writeBalance(connection, request.playerUuid, after, request.createdAt)

        val entry = EconomyLedgerEntry(
            transactionId = request.transactionId,
            playerUuid = request.playerUuid,
            counterpartyUuid = null,
            reason = request.reason,
            amount = request.amount,
            balanceBefore = before,
            balanceAfter = after,
            sourceServerId = request.sourceServerId,
            actorUuid = request.actorUuid,
            createdAt = request.createdAt,
        )
        insertLedgerEntry(connection, entry)
        EconomyBalanceUpdate(before, after, entry)
    }

    override fun applyTransfer(request: EconomyTransferRequest): EconomyTransferUpdate {
        require(request.senderUuid != request.receiverUuid) { "A player cannot transfer money to themselves" }
        return transaction { connection ->
            val ordered = listOf(request.senderUuid, request.receiverUuid).sorted()
            ordered.forEach { ensureBalanceRow(connection, it, request.createdAt) }
            val balances = ordered.associateWith { lockBalance(connection, it) }
            val senderBefore = requireNotNull(balances[request.senderUuid])
            val receiverBefore = requireNotNull(balances[request.receiverUuid])

            val senderAfter = withdraw(senderBefore, request.amount, "That player does not have enough TitanCoins for this transfer.")
            val receiverAfter = receiverBefore.plus(request.amount)
            validateBalance(senderAfter)
            validateBalance(receiverAfter)

            writeBalance(connection, request.senderUuid, senderAfter, request.createdAt)
            writeBalance(connection, request.receiverUuid, receiverAfter, request.createdAt)

            val senderEntry = EconomyLedgerEntry(
                transactionId = request.transactionId,
                playerUuid = request.senderUuid,
                counterpartyUuid = request.receiverUuid,
                reason = request.reason,
                amount = request.amount,
                balanceBefore = senderBefore,
                balanceAfter = senderAfter,
                sourceServerId = request.sourceServerId,
                actorUuid = request.actorUuid,
                createdAt = request.createdAt,
            )
            val receiverEntry = EconomyLedgerEntry(
                transactionId = request.transactionId,
                playerUuid = request.receiverUuid,
                counterpartyUuid = request.senderUuid,
                reason = request.reason,
                amount = request.amount,
                balanceBefore = receiverBefore,
                balanceAfter = receiverAfter,
                sourceServerId = request.sourceServerId,
                actorUuid = request.actorUuid,
                createdAt = request.createdAt,
            )
            insertLedgerEntry(connection, senderEntry)
            insertLedgerEntry(connection, receiverEntry)

            EconomyTransferUpdate(
                request.transactionId,
                EconomyBalanceUpdate(senderBefore, senderAfter, senderEntry),
                EconomyBalanceUpdate(receiverBefore, receiverAfter, receiverEntry),
            )
        }
    }

    private fun ensureBalanceRow(connection: Connection, playerUuid: UUID, at: Instant) {
        connection.prepareStatement(
            "INSERT INTO economy_balances (player_uuid, balance, created_at, updated_at) VALUES (?, 0, ?, ?) " +
                "ON DUPLICATE KEY UPDATE balance = balance",
        ).use { statement ->
            statement.setBytes(1, playerUuid.toBytes())
            statement.setUtc(2, at)
            statement.setUtc(3, at)
            statement.executeUpdate()
        }
    }

    private fun lockBalance(connection: Connection, playerUuid: UUID): Money =
        connection.prepareStatement("SELECT balance FROM economy_balances WHERE player_uuid = ? FOR UPDATE").use { statement ->
            statement.setBytes(1, playerUuid.toBytes())
            statement.executeQuery().use { result ->
                check(result.next()) { "The balance row disappeared while it was being updated" }
                result.money("balance")
            }
        }

    private fun writeBalance(connection: Connection, playerUuid: UUID, balance: Money, at: Instant) {
        connection.prepareStatement("UPDATE economy_balances SET balance = ?, updated_at = ? WHERE player_uuid = ?").use { statement ->
            statement.setLong(1, balance.minorUnits)
            statement.setUtc(2, at)
            statement.setBytes(3, playerUuid.toBytes())
            check(statement.executeUpdate() == 1) { "The balance row disappeared while it was being updated" }
        }
    }

    private fun insertLedgerEntry(connection: Connection, entry: EconomyLedgerEntry) {
        connection.prepareStatement(
            "INSERT INTO economy_transactions (transaction_id, player_uuid, counterparty_uuid, type, amount, " +
                "balance_before, balance_after, source_server_id, actor_uuid, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setBytes(1, entry.transactionId.toBytes())
            statement.setBytes(2, entry.playerUuid.toBytes())
            statement.setUuidOrNull(3, entry.counterpartyUuid)
            statement.setString(4, entry.reason.name)
            statement.setLong(5, entry.amount.minorUnits)
            statement.setLong(6, entry.balanceBefore.minorUnits)
            statement.setLong(7, entry.balanceAfter.minorUnits)
            statement.setString(8, entry.sourceServerId)
            statement.setUuidOrNull(9, entry.actorUuid)
            statement.setUtc(10, entry.createdAt)
            statement.executeUpdate()
        }
    }

    private fun withdraw(balance: Money, amount: Money, message: String): Money {
        if (balance < amount) throw EconomyInsufficientFundsException(message)
        return balance.minus(amount)
    }

    private fun validateBalance(balance: Money) {
        if (balance.isNegative()) throw EconomyNegativeBalanceException("A balance can never be negative.")
    }

    private fun <T> transaction(block: (Connection) -> T): T {
        dataSource.connection.use { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                return block(connection).also { connection.commit() }
            } catch (failure: Throwable) {
                try {
                    connection.rollback()
                } catch (rollback: Throwable) {
                    failure.addSuppressed(rollback)
                }
                throw failure
            } finally {
                connection.autoCommit = previousAutoCommit
            }
        }
    }

    private fun PreparedStatement.setUtc(index: Int, instant: Instant) =
        setObject(index, LocalDateTime.ofInstant(instant, ZoneOffset.UTC))

    private fun PreparedStatement.setUuidOrNull(index: Int, uuid: UUID?) =
        if (uuid == null) setNull(index, java.sql.Types.BINARY) else setBytes(index, uuid.toBytes())

    private fun ResultSet.money(column: String): Money = Money.ofMinor(getLong(column))

    private fun UUID.toBytes(): ByteArray = ByteBuffer.allocate(16).putLong(mostSignificantBits).putLong(leastSignificantBits).array()
    private fun ByteArray.toUuid(): UUID = ByteBuffer.wrap(this).let { UUID(it.long, it.long) }

    companion object {
        const val MAX_TOP_LIMIT = 50
    }
}
