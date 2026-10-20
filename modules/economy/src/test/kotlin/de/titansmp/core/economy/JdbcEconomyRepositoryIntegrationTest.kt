package de.titansmp.core.economy

import de.titansmp.api.economy.EconomyReason
import de.titansmp.api.economy.Money
import de.titansmp.core.database.DatabaseMigrator
import de.titansmp.core.database.DatabaseService
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mariadb.MariaDBContainer
import java.nio.ByteBuffer
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcEconomyRepositoryIntegrationTest {
    @Container
    private val mariaDb = MariaDBContainer("mariadb:11.4")

    private lateinit var dataSource: org.mariadb.jdbc.MariaDbDataSource
    private lateinit var repository: JdbcEconomyRepository
    private val concurrencyExecutor = Executors.newFixedThreadPool(12) { task ->
        Thread(task, "economy-integration-test").apply { isDaemon = true }
    }

    @BeforeEach
    fun setUp() {
        if (!::dataSource.isInitialized) {
            dataSource = org.mariadb.jdbc.MariaDbDataSource().apply {
                setUrl(mariaDb.jdbcUrl)
                setUser(mariaDb.username)
                setPassword(mariaDb.password)
            }
            DatabaseMigrator().migrate(object : DatabaseService {
                override val dataSource = this@JdbcEconomyRepositoryIntegrationTest.dataSource
                override fun start() = Unit
                override fun close() = Unit
            })
        }
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("DELETE FROM economy_transactions")
                statement.executeUpdate("DELETE FROM economy_balances")
                statement.executeUpdate("DELETE FROM players")
            }
        }
        repository = JdbcEconomyRepository(dataSource)
    }

    @AfterAll
    fun tearDown() {
        concurrencyExecutor.shutdownNow()
    }

    @Test
    fun `new player reads zero without a balance row`() {
        val player = insertPlayer("NewPlayer")
        assertEquals(Money.ZERO, repository.findBalance(player))
        assertEquals(0, count("economy_balances"))
    }

    @Test
    fun `deposit withdraw and set update the row and append audit entries`() {
        val player = insertPlayer("AuditPlayer")
        val admin = UUID.randomUUID()

        repository.applyChange(request(player, EconomyOperation.DEPOSIT, "10.50", EconomyReason.ADMIN_GIVE, admin))
        repository.applyChange(request(player, EconomyOperation.WITHDRAW, "0.50", EconomyReason.ADMIN_TAKE, admin))
        repository.applyChange(request(player, EconomyOperation.SET, "2.00", EconomyReason.ADMIN_SET, admin))

        assertEquals(Money.of("2.00"), repository.findBalance(player))
        val rows = ledgerRows(player, 10)
        assertEquals(listOf("ADMIN_SET", "ADMIN_TAKE", "ADMIN_GIVE"), rows.map { it.type })
        assertEquals(listOf(200L, 50L, 1050L), rows.map { it.amount })
        assertEquals(listOf(1000L, 1050L, 0L), rows.map { it.before })
        assertEquals(listOf(200L, 1000L, 1050L), rows.map { it.after })
        assertEquals(listOf(admin, admin, admin), rows.map { it.actor })
    }

    @Test
    fun `insufficient withdrawal rolls back without audit or balance change`() {
        val player = insertPlayer("PoorPlayer")
        repository.applyChange(request(player, EconomyOperation.DEPOSIT, "3.00", EconomyReason.ADMIN_GIVE, null))

        assertThrows(EconomyInsufficientFundsException::class.java) {
            repository.applyChange(request(player, EconomyOperation.WITHDRAW, "5.00", EconomyReason.ADMIN_TAKE, null))
        }

        assertEquals(Money.of("3.00"), repository.findBalance(player))
        assertEquals(1, count("economy_transactions"))
    }

    @Test
    fun `transfer moves money atomically and writes two correlated audit rows`() {
        val sender = insertPlayer("Sender")
        val receiver = insertPlayer("Receiver")
        repository.applyChange(request(sender, EconomyOperation.SET, "100.00", EconomyReason.ADMIN_SET, null))
        repository.applyChange(request(receiver, EconomyOperation.SET, "50.00", EconomyReason.ADMIN_SET, null))

        val transactionId = UUID.randomUUID()
        val update = repository.applyTransfer(
            EconomyTransferRequest(transactionId, sender, receiver, Money.of("30.00"), EconomyReason.PAYMENT, null, Instant.now(), "smp-01"),
        )

        assertEquals(Money.of("70.00"), repository.findBalance(sender))
        assertEquals(Money.of("80.00"), repository.findBalance(receiver))
        assertEquals(Money.of("70.00"), update.sender.newBalance)
        assertEquals(Money.of("80.00"), update.receiver.newBalance)

        val rows = dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT player_uuid, counterparty_uuid, type, amount, balance_before, balance_after FROM economy_transactions WHERE transaction_id = ?").use { statement ->
                statement.setBytes(1, transactionId.asBytes())
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                Row(
                                    player = result.getBytes("player_uuid").toUuid(),
                                    counterparty = result.getBytes("counterparty_uuid")?.toUuid(),
                                    type = result.getString("type"),
                                    amount = result.getLong("amount"),
                                    before = result.getLong("balance_before"),
                                    after = result.getLong("balance_after"),
                                ),
                            )
                        }
                    }
                }
            }
        }
        assertEquals(2, rows.size)
        assertEquals(setOf(sender, receiver), rows.map { it.player }.toSet())
        assertEquals(sender, rows.single { it.player == receiver }.counterparty)
        assertEquals(receiver, rows.single { it.player == sender }.counterparty)
        assertTrue(rows.all { it.type == "PAYMENT" && it.amount == 3000L })
    }

    @Test
    fun `failed transfer rolls back both sides and all audit rows`() {
        val sender = insertPlayer("Sender")
        val receiver = insertPlayer("Receiver")
        repository.applyChange(request(sender, EconomyOperation.SET, "10.00", EconomyReason.ADMIN_SET, null))

        assertThrows(EconomyInsufficientFundsException::class.java) {
            repository.applyTransfer(
                EconomyTransferRequest(UUID.randomUUID(), sender, receiver, Money.of("20.00"), EconomyReason.PAYMENT, null, Instant.now(), "smp-01"),
            )
        }

        assertEquals(Money.of("10.00"), repository.findBalance(sender))
        assertEquals(Money.ZERO, repository.findBalance(receiver))
        assertEquals(1, count("economy_transactions"))
    }

    @Test
    fun `replaying an auction transfer ID does not move money twice`() {
        val buyer=insertPlayer("AuctionBuyer");val seller=insertPlayer("AuctionSeller")
        repository.applyChange(request(buyer,EconomyOperation.SET,"20.00",EconomyReason.ADMIN_SET,null))
        val stableId=UUID.randomUUID()
        val transfer=EconomyTransferRequest(stableId,buyer,seller,Money.of("7.50"),EconomyReason.AUCTION_HOUSE,null,Instant.now(),"smp-01")
        val first=repository.applyTransfer(transfer)
        val replay=repository.applyTransfer(transfer)
        assertEquals(first.sender.oldBalance,replay.sender.oldBalance)
        assertEquals(Money.of("12.50"),repository.findBalance(buyer))
        assertEquals(Money.of("7.50"),repository.findBalance(seller))
        assertEquals(3,count("economy_transactions"))
    }

    @Test
    fun `balancetop uses sql ordering with a deterministic uuid tie breaker and limit`() {
        val tiedLow = insertPlayer("TiedLow", UUID(0x1000L, 0x1000L))
        val tiedHigh = insertPlayer("TiedHigh", UUID(0x2000L, 0x2000L))
        val richest = insertPlayer("Richest")
        val third = insertPlayer("Third")
        repository.applyChange(request(tiedLow, EconomyOperation.SET, "100.00", EconomyReason.ADMIN_SET, null))
        repository.applyChange(request(tiedHigh, EconomyOperation.SET, "100.00", EconomyReason.ADMIN_SET, null))
        repository.applyChange(request(richest, EconomyOperation.SET, "500.00", EconomyReason.ADMIN_SET, null))
        repository.applyChange(request(third, EconomyOperation.SET, "50.00", EconomyReason.ADMIN_SET, null))
        repository.applyChange(request(insertPlayer("Zero"), EconomyOperation.SET, "0", EconomyReason.ADMIN_SET, null))

        val top = repository.topBalances(10)

        assertEquals(listOf(richest, minOf(tiedLow, tiedHigh), maxOf(tiedLow, tiedHigh), third), top.map { it.playerUuid })
        assertEquals(listOf(1, 2, 3, 4), top.map { it.rank })
        assertEquals(listOf(50000L, 10000L, 10000L, 5000L), top.map { it.balance.minorUnits })
        assertEquals("Richest", top.first().username)
        assertEquals(2, repository.topBalances(2).size)
    }

    @Test
    fun `balancetop returns fewer than ten rows when fewer players exist`() {
        repeat(3) { index ->
            val player = insertPlayer("Top$index")
            repository.applyChange(request(player, EconomyOperation.SET, "${(index + 1) * 10}", EconomyReason.ADMIN_SET, null))
        }
        assertEquals(3, repository.topBalances(10).size)
    }

    @Test
    fun `parallel deposits to the same player never lose an update`() {
        val player = insertPlayer("ConcurrentDeposit")
        runConcurrently(20) {
            repository.applyChange(request(player, EconomyOperation.DEPOSIT, "1.00", EconomyReason.ADMIN_GIVE, null))
        }
        assertEquals(Money.of("20.00"), repository.findBalance(player))
        assertEquals(20, count("economy_transactions"))
    }

    @Test
    fun `parallel withdrawals never drive a balance below zero`() {
        val player = insertPlayer("ConcurrentWithdraw")
        repository.applyChange(request(player, EconomyOperation.SET, "10.00", EconomyReason.ADMIN_SET, null))
        runConcurrently(10) {
            repository.applyChange(request(player, EconomyOperation.WITHDRAW, "1.00", EconomyReason.ADMIN_TAKE, null))
        }
        assertEquals(Money.ZERO, repository.findBalance(player))
    }

    @Test
    fun `parallel payments to the same receiver never lose a movement`() {
        val alice = insertPlayer("Alice")
        val bob = insertPlayer("Bob")
        repository.applyChange(request(alice, EconomyOperation.SET, "100.00", EconomyReason.ADMIN_SET, null))

        runConcurrently(10) {
            repository.applyTransfer(
                EconomyTransferRequest(
                    UUID.randomUUID(),
                    alice,
                    bob,
                    Money.of("1.00"),
                    EconomyReason.PAYMENT,
                    null,
                    Instant.now(),
                    "smp-01",
                ),
            )
        }

        assertEquals(Money.of("90.00"), repository.findBalance(alice))
        assertEquals(Money.of("10.00"), repository.findBalance(bob))
        assertEquals(20, count("economy_transactions"))
    }

    @Test
    fun `parallel admin changes are serialized per player`() {
        val player = insertPlayer("ParallelAdmin")
        runConcurrently(30) {
            repository.applyChange(request(player, EconomyOperation.DEPOSIT, "0.01", EconomyReason.ADMIN_GIVE, null))
        }
        assertEquals(Money.of("0.30"), repository.findBalance(player))
    }

    private fun runConcurrently(count: Int, block: (Int) -> Unit) {
        val start = CountDownLatch(1)
        val futures = (0 until count).map { index ->
            concurrencyExecutor.submit {
                start.await()
                block(index)
            }
        }
        start.countDown()
        futures.forEach { it.get(30, TimeUnit.SECONDS) }
    }

    private fun request(
        player: UUID,
        operation: EconomyOperation,
        amount: String,
        reason: EconomyReason,
        actor: UUID?,
    ) = EconomyChangeRequest(
        UUID.randomUUID(),
        player,
        operation,
        Money.of(amount),
        reason,
        actor,
        Instant.now(),
        "smp-01",
    )

    private fun insertPlayer(name: String, uuid: UUID = UUID.randomUUID()): UUID {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO players (uuid, username, first_join, last_seen, current_server, online) " +
                    "VALUES (?, ?, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), 'smp-01', TRUE)",
            ).use { statement ->
                statement.setBytes(1, uuid.asBytes())
                statement.setString(2, name)
                statement.executeUpdate()
            }
        }
        return uuid
    }

    private fun count(table: String): Int = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM $table").use { result ->
                result.next()
                result.getInt(1)
            }
        }
    }

    private fun ledgerRows(player: UUID, limit: Int): List<Row> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT player_uuid, counterparty_uuid, type, amount, balance_before, balance_after, actor_uuid " +
                "FROM economy_transactions WHERE player_uuid = ? ORDER BY created_at DESC LIMIT ?",
        ).use { statement ->
            statement.setBytes(1, player.asBytes())
            statement.setInt(2, limit)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            Row(
                                player = result.getBytes("player_uuid").toUuid(),
                                counterparty = result.getBytes("counterparty_uuid")?.toUuid(),
                                type = result.getString("type"),
                                amount = result.getLong("amount"),
                                before = result.getLong("balance_before"),
                                after = result.getLong("balance_after"),
                                actor = result.getBytes("actor_uuid")?.toUuid(),
                            ),
                        )
                    }
                }
            }
        }
    }

    private data class Row(
        val player: UUID,
        val counterparty: UUID?,
        val type: String,
        val amount: Long,
        val before: Long,
        val after: Long,
        val actor: UUID? = null,
    )

    private fun UUID.asBytes(): ByteArray = ByteBuffer.allocate(16).putLong(mostSignificantBits).putLong(leastSignificantBits).array()
    private fun ByteArray.toUuid(): UUID = ByteBuffer.wrap(this).let { UUID(it.long, it.long) }
}
