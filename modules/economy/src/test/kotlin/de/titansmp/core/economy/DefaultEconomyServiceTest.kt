package de.titansmp.core.economy

import de.titansmp.api.economy.EconomyBalanceChanged
import de.titansmp.api.economy.EconomyBalanceEntry
import de.titansmp.api.economy.EconomyException
import de.titansmp.api.economy.EconomyPermissionService
import de.titansmp.api.economy.EconomyPermissions
import de.titansmp.api.economy.EconomyReason
import de.titansmp.api.economy.EconomyService
import de.titansmp.api.economy.Money
import de.titansmp.api.event.EventBus
import de.titansmp.api.event.EventEnvelope
import de.titansmp.api.event.EventListener
import de.titansmp.api.event.EventSubscription
import de.titansmp.api.event.EventType
import de.titansmp.api.event.NetworkEvent
import de.titansmp.api.player.NetworkPlayer
import de.titansmp.api.player.PlayerNotFoundException
import de.titansmp.api.player.PlayerService
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [CompletionStage] has no blocking `join`; tests may block on the completed future. */
private fun <T> CompletionStage<T>.join(): T = toCompletableFuture().join()

class DefaultEconomyServiceTest {
    private val now = Instant.parse("2026-10-20T12:00:00Z")
    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()
    private val charlie = UUID.randomUUID()
    private val admin = UUID.randomUUID()
    private val repository = InMemoryEconomyRepository()
    private val players = FakePlayerService()
    private val bus = RecordingEventBus()
    private val granted = mutableSetOf<Pair<UUID, String>>()
    private val permissions = EconomyPermissionService { id, node ->
        CompletableFuture.completedFuture((id to node) in granted)
    }
    private val service = DefaultEconomyService(
        repository,
        permissions,
        players,
        bus,
        Executor(Runnable::run),
        Clock.fixed(now, ZoneOffset.UTC),
    )

    init {
        players.put(alice, "Alice", "smp-01")
        players.put(bob, "Bob", "lobby-01")
        players.put(charlie, "Charlie", "smp-02", online = false)
        grant(alice, EconomyPermissions.BALANCE, EconomyPermissions.PAY, EconomyPermissions.BALANCETOP)
        grant(bob, EconomyPermissions.BALANCE, EconomyPermissions.PAY, EconomyPermissions.BALANCETOP)
        grant(admin, EconomyPermissions.ADMIN)
    }

    @Test
    fun `new player has zero balance and reading does not create a row`() {
        assertEquals(Money.ZERO, service.getBalance(charlie).join())
        assertTrue(repository.balances.isEmpty())
    }

    @Test
    fun `deposit withdraw and set update balances and append audit entries`() {
        val deposit = service.deposit(alice, Money.of("10.50"), EconomyReason.ADMIN_GIVE, admin, "smp-01").join()
        assertEquals(Money.of("0.00"), deposit.oldBalance)
        assertEquals(Money.of("10.50"), deposit.newBalance)
        assertEquals(EconomyReason.ADMIN_GIVE, deposit.reason)
        assertEquals(admin, deposit.actorUuid)

        service.withdraw(alice, Money.of("0.50"), EconomyReason.ADMIN_TAKE, admin, "smp-01").join()
        service.setBalance(alice, Money.of("1.00"), EconomyReason.ADMIN_SET, admin, "smp-01").join()

        assertEquals(Money.of("1.00"), service.getBalance(alice).join())
        assertEquals(listOf("10.50", "0.50", "1.00"), repository.ledger.map { it.amount.format() })
        assertEquals(listOf("0.00", "10.50", "10.00"), repository.ledger.map { it.balanceBefore.format() })
        assertEquals(listOf("10.50", "10.00", "1.00"), repository.ledger.map { it.balanceAfter.format() })
    }

    @Test
    fun `set allows zero while deposit and withdrawal reject zero and negatives`() {
        service.setBalance(alice, Money.ZERO, EconomyReason.ADMIN_SET, admin, "smp-01").join()
        assertEquals(Money.ZERO, service.getBalance(alice).join())

        assertCode(EconomyException.Code.INVALID_AMOUNT) {
            service.deposit(alice, Money.ZERO, EconomyReason.ADMIN_GIVE, admin, "smp-01")
        }
        assertCode(EconomyException.Code.INVALID_AMOUNT) {
            service.withdraw(alice, Money.ofMinor(-1), EconomyReason.ADMIN_TAKE, admin, "smp-01")
        }
        assertCode(EconomyException.Code.INVALID_AMOUNT) {
            service.setBalance(alice, Money.ofMinor(-1), EconomyReason.ADMIN_SET, admin, "smp-01")
        }
    }

    @Test
    fun `withdrawal below zero is rejected and leaves the balance untouched`() {
        service.deposit(alice, Money.of("3.00"), EconomyReason.ADMIN_GIVE, admin, "smp-01").join()
        assertCode(EconomyException.Code.INSUFFICIENT_FUNDS) {
            service.withdraw(alice, Money.of("5.00"), EconomyReason.ADMIN_TAKE, admin, "smp-01")
        }
        assertEquals(Money.of("3.00"), service.getBalance(alice).join())
        assertEquals(1, repository.ledger.size)
    }

    @Test
    fun `addition overflow is rejected without changing the balance`() {
        repository.balances[alice] = Money.ofMinor(Long.MAX_VALUE - 1)
        assertCode(EconomyException.Code.OVERFLOW) {
            service.deposit(alice, Money.ofMinor(10), EconomyReason.ADMIN_GIVE, admin, "smp-01")
        }
        assertEquals(Money.ofMinor(Long.MAX_VALUE - 1), service.getBalance(alice).join())
        assertTrue(repository.ledger.isEmpty())
    }

    @Test
    fun `successful payment is atomic on both sides and publishes one event per account`() {
        repository.balances[alice] = Money.of("100.00")
        repository.balances[bob] = Money.of("50.00")

        val transfer = service.pay(alice, bob, Money.of("30.00"), "smp-01").join()

        assertEquals(Money.of("70.00"), service.getBalance(alice).join())
        assertEquals(Money.of("80.00"), service.getBalance(bob).join())
        assertEquals(transfer.transactionId, transfer.sender.transactionId)
        assertEquals(transfer.transactionId, transfer.receiver.transactionId)
        assertEquals(bob, transfer.sender.counterpartyUuid)
        assertEquals(alice, transfer.receiver.counterpartyUuid)
        assertEquals(2, repository.ledger.size)
        assertTrue(repository.ledger.all { it.transactionId == transfer.transactionId })

        val events = bus.events.filterIsInstance<EconomyBalanceChanged>()
        assertEquals(2, events.size)
        assertTrue(events.all { it.transactionId == transfer.transactionId })
        assertEquals(setOf(alice, bob), events.map { it.playerUuid }.toSet())
        assertEquals("Bob", events.first { it.playerUuid == alice }.counterpartyName)
        assertEquals("Alice", events.first { it.playerUuid == bob }.counterpartyName)
        assertEquals("smp-01", events.first().sourceServerId)
    }

    @Test
    fun `payment rejects insufficient funds self transfer zero negative unknown and offline targets`() {
        repository.balances[alice] = Money.of("10.00")
        repository.balances[bob] = Money.of("50.00")

        assertCode(EconomyException.Code.INSUFFICIENT_FUNDS) { service.pay(alice, bob, Money.of("11.00"), "smp-01") }
        assertCode(EconomyException.Code.SELF_TRANSFER) { service.pay(alice, alice, Money.of("1.00"), "smp-01") }
        assertCode(EconomyException.Code.INVALID_AMOUNT) { service.pay(alice, bob, Money.ZERO, "smp-01") }
        assertCode(EconomyException.Code.INVALID_AMOUNT) { service.pay(alice, bob, Money.ofMinor(-1), "smp-01") }
        assertCode(EconomyException.Code.PLAYER_NOT_FOUND) { service.pay(alice, UUID.randomUUID(), Money.of("1.00"), "smp-01") }
        assertCode(EconomyException.Code.TARGET_NOT_ONLINE) { service.pay(alice, charlie, Money.of("1.00"), "smp-01") }

        assertEquals(Money.of("10.00"), service.getBalance(alice).join())
        assertEquals(Money.of("50.00"), service.getBalance(bob).join())
        assertTrue(repository.ledger.isEmpty())
    }

    @Test
    fun `payment to an online player on another server works`() {
        repository.balances[alice] = Money.of("10.00")
        service.pay(alice, bob, Money.of("5.00"), "smp-01").join()
        assertEquals(Money.of("5.00"), service.getBalance(alice).join())
        assertEquals(Money.of("5.00"), service.getBalance(bob).join())
    }

    @Test
    fun `pay permission is enforced without rank lookups`() {
        granted.clear()
        assertCode(EconomyException.Code.NO_PERMISSION) { service.pay(alice, bob, Money.of("1.00"), "smp-01") }
    }

    @Test
    fun `balance self and others use separate permissions`() {
        assertEquals(Money.ZERO, service.getBalanceFor(alice, alice).join())
        assertCode(EconomyException.Code.NO_PERMISSION) { service.getBalanceFor(alice, bob) }

        grant(admin, EconomyPermissions.BALANCE_OTHERS)
        assertEquals(Money.ZERO, service.getBalanceFor(admin, bob).join())
    }

    @Test
    fun `balancetop checks permission and clamps the requested limit`() {
        assertCode(EconomyException.Code.NO_PERMISSION) { service.topBalances(charlie, 10) }
        granted += charlie to EconomyPermissions.BALANCETOP
        assertEquals(emptyList(), service.topBalances(charlie, 0).join())
        service.topBalances(charlie, 999).join()
        assertEquals(EconomyService.MAX_TOP_LIMIT, repository.lastTopLimit)
    }

    @Test
    fun `admin commands require admin permission and record the actor`() {
        val give = service.adminGive(bob, Money.of("10.00"), admin, "smp-01").join()
        assertEquals(EconomyReason.ADMIN_GIVE, give.reason)
        assertEquals(admin, give.actorUuid)

        val take = service.adminTake(bob, Money.of("2.00"), admin, "smp-01").join()
        assertEquals(EconomyReason.ADMIN_TAKE, take.reason)
        assertEquals(Money.of("8.00"), take.newBalance)

        val set = service.adminSet(bob, Money.ZERO, admin, "smp-01").join()
        assertEquals(EconomyReason.ADMIN_SET, set.reason)
        assertEquals(Money.ZERO, set.newBalance)

        assertCode(EconomyException.Code.NO_PERMISSION) {
            service.adminGive(bob, Money.of("1.00"), alice, "smp-01")
        }
    }

    @Test
    fun `admin take below zero is rejected`() {
        service.adminGive(bob, Money.of("3.00"), admin, "smp-01").join()
        assertCode(EconomyException.Code.INSUFFICIENT_FUNDS) {
            service.adminTake(bob, Money.of("5.00"), admin, "smp-01")
        }
        assertEquals(Money.of("3.00"), service.getBalance(bob).join())
    }

    @Test
    fun `permission service failure is surfaced as permission check failure`() {
        val failing = DefaultEconomyService(
            repository,
            EconomyPermissionService { _, _ -> CompletableFuture.failedFuture(IllegalStateException("LuckPerms down")) },
            players,
            bus,
            Executor(Runnable::run),
            Clock.fixed(now, ZoneOffset.UTC),
        )
        assertCode(EconomyException.Code.PERMISSION_CHECK_FAILED) {
            failing.pay(alice, bob, Money.of("1.00"), "smp-01")
        }
    }

    @Test
    fun `redis publish failure never rolls back a committed payment`() {
        repository.balances[alice] = Money.of("10.00")
        bus.failure = IllegalStateException("Redis unavailable")

        service.pay(alice, bob, Money.of("5.00"), "smp-01").join()

        assertEquals(Money.of("5.00"), service.getBalance(alice).join())
        assertEquals(Money.of("5.00"), service.getBalance(bob).join())
        assertTrue(repository.ledger.isNotEmpty())
    }

    @Test
    fun `transaction ids are unique for independent changes`() {
        val first = service.adminGive(bob, Money.of("1.00"), admin, "smp-01").join()
        val second = service.adminGive(bob, Money.of("1.00"), admin, "smp-01").join()
        assertTrue(first.transactionId != second.transactionId)
        assertNull(first.counterpartyUuid)
    }

    private fun grant(id: UUID, vararg nodes: String) {
        nodes.forEach { granted += id to it }
    }

    private fun assertCode(expected: EconomyException.Code, block: () -> CompletionStage<*>) {
        val failure = assertFailsWith<CompletionException> { block().toCompletableFuture().join() }
        val cause = assertIs<EconomyException>(failure.cause)
        assertEquals(expected, cause.code)
    }

    private class InMemoryEconomyRepository : EconomyRepository {
        val balances = mutableMapOf<UUID, Money>()
        val ledger = mutableListOf<EconomyLedgerEntry>()
        var lastTopLimit: Int = 0

        override fun findBalance(playerUuid: UUID): Money = balances[playerUuid] ?: Money.ZERO

        override fun topBalances(limit: Int): List<EconomyBalanceEntry> {
            lastTopLimit = limit
            return balances.entries
                .filter { it.value.isPositive() }
                .sortedWith(compareByDescending<Map.Entry<UUID, Money>> { it.value }.thenBy { it.key })
                .take(limit)
                .mapIndexed { index, entry ->
                    EconomyBalanceEntry(index + 1, entry.key, "Player-${entry.key.toString().take(4)}", entry.value)
                }
        }

        override fun applyChange(request: EconomyChangeRequest): EconomyBalanceUpdate {
            val before = findBalance(request.playerUuid)
            val after = when (request.operation) {
                EconomyOperation.DEPOSIT -> before.plus(request.amount)
                EconomyOperation.WITHDRAW -> {
                    if (before < request.amount) throw EconomyInsufficientFundsException("insufficient")
                    before.minus(request.amount)
                }
                EconomyOperation.SET -> request.amount
            }
            if (after.isNegative()) throw EconomyNegativeBalanceException("negative")
            balances[request.playerUuid] = after
            val entry = EconomyLedgerEntry(
                request.transactionId, request.playerUuid, null, request.reason, request.amount,
                before, after, request.sourceServerId, request.actorUuid, request.createdAt,
            )
            ledger += entry
            return EconomyBalanceUpdate(before, after, entry)
        }

        override fun applyTransfer(request: EconomyTransferRequest): EconomyTransferUpdate {
            val senderBefore = findBalance(request.senderUuid)
            val receiverBefore = findBalance(request.receiverUuid)
            if (senderBefore < request.amount) throw EconomyInsufficientFundsException("insufficient")
            val senderAfter = senderBefore.minus(request.amount)
            val receiverAfter = receiverBefore.plus(request.amount)
            balances[request.senderUuid] = senderAfter
            balances[request.receiverUuid] = receiverAfter
            val senderEntry = EconomyLedgerEntry(
                request.transactionId, request.senderUuid, request.receiverUuid, request.reason, request.amount,
                senderBefore, senderAfter, request.sourceServerId, request.actorUuid, request.createdAt,
            )
            val receiverEntry = EconomyLedgerEntry(
                request.transactionId, request.receiverUuid, request.senderUuid, request.reason, request.amount,
                receiverBefore, receiverAfter, request.sourceServerId, request.actorUuid, request.createdAt,
            )
            ledger += senderEntry
            ledger += receiverEntry
            return EconomyTransferUpdate(
                request.transactionId,
                EconomyBalanceUpdate(senderBefore, senderAfter, senderEntry),
                EconomyBalanceUpdate(receiverBefore, receiverAfter, receiverEntry),
            )
        }
    }

    private class FakePlayerService : PlayerService {
        private val players = mutableMapOf<UUID, NetworkPlayer>()

        fun put(uuid: UUID, username: String, server: String, online: Boolean = true) {
            val instant = Instant.parse("2026-10-20T12:00:00Z")
            players[uuid] = NetworkPlayer(uuid, username, instant, instant, if (online) server else null, online)
        }

        override fun getPlayer(uuid: UUID): NetworkPlayer = findPlayer(uuid) ?: throw PlayerNotFoundException(uuid)
        override fun findPlayer(uuid: UUID): NetworkPlayer? = players[uuid]
        override fun findPlayerByName(username: String): NetworkPlayer? = players.values.firstOrNull { it.username.equals(username, true) }
        override fun createOrLoad(uuid: UUID, username: String): NetworkPlayer = getPlayer(uuid)
        override fun updateLastSeen(uuid: UUID) = Unit
        override fun setOnline(uuid: UUID, serverId: String) = Unit
        override fun setOffline(uuid: UUID) = Unit
        override fun setOffline(uuid: UUID, expectedServerId: String): Boolean = true
    }

    private class RecordingEventBus : EventBus {
        val events = mutableListOf<NetworkEvent>()
        var failure: Throwable? = null
        override fun publish(event: NetworkEvent): CompletionStage<Unit> {
            events += event
            return failure?.let { CompletableFuture.failedFuture(it) } ?: CompletableFuture.completedFuture(Unit)
        }
        override fun subscribe(eventType: EventType, listener: EventListener) = EventSubscription {}
        override fun close() = Unit
    }
}
