package de.titansmp.paper.economy

import de.titansmp.api.economy.EconomyBalanceChanged
import de.titansmp.api.economy.EconomyBalanceEntry
import de.titansmp.api.economy.EconomyChange
import de.titansmp.api.economy.EconomyException
import de.titansmp.api.economy.EconomyReason
import de.titansmp.api.economy.EconomyService
import de.titansmp.api.economy.EconomyTransfer
import de.titansmp.api.economy.Money
import de.titansmp.api.event.EventBus
import de.titansmp.api.event.EventEnvelope
import de.titansmp.api.event.EventListener
import de.titansmp.api.event.EventMetadata
import de.titansmp.api.event.EventSubscription
import de.titansmp.api.event.EventType
import de.titansmp.api.event.NetworkEvent
import de.titansmp.api.player.NetworkPlayer
import de.titansmp.api.player.PlayerNotFoundException
import de.titansmp.api.player.PlayerService
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor

class EconomyPaperModuleTest {
    private lateinit var server: ServerMock
    private lateinit var plugin: TestPaperPlugin
    private lateinit var alice: PlayerMock
    private lateinit var bob: PlayerMock
    private lateinit var charlieId: UUID
    private lateinit var service: FakeEconomyService
    private lateinit var players: FakePlayerService
    private lateinit var module: EconomyPaperModule

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        plugin = MockBukkit.load(TestPaperPlugin::class.java)
        alice = server.addPlayer("Alice")
        bob = server.addPlayer("Bob")
        charlieId = UUID.randomUUID()
        service = FakeEconomyService()
        service.balances[alice.uniqueId] = Money.of("1250.50")
        service.balances[bob.uniqueId] = Money.of("50.00")
        players = FakePlayerService().apply {
            put(alice.uniqueId, alice.name, "smp-01", online = true)
            put(bob.uniqueId, bob.name, "lobby-01", online = true)
            put(charlieId, "Charlie", "smp-02", online = false)
        }
        module = EconomyPaperModule(plugin, service, players, RecordingEventBus(), "smp-01", Executor(Runnable::run))
    }

    @AfterEach
    fun tearDown() {
        module.close()
        MockBukkit.unmock()
    }

    @Test
    fun `balance shows the exact formatted self balance`() {
        command(alice, "balance")

        assertEquals("Your balance: 1,250.50 TitanCoins", alice.nextText())
        assertEquals(listOf("getBalanceFor:${alice.uniqueId}:${alice.uniqueId}"), service.calls)
    }

    @Test
    fun `balance another player works for an offline identity`() {
        service.balances[charlieId] = Money.of("98500.50")

        command(alice, "balance", "Charlie")

        assertEquals("Charlie's balance: 98,500.50 TitanCoins", alice.nextText())
        assertEquals(listOf("getBalanceFor:${alice.uniqueId}:$charlieId"), service.calls)
    }

    @Test
    fun `balance others permission failure is shown`() {
        service.balanceFailure = EconomyException(EconomyException.Code.NO_PERMISSION, "You do not have permission to do that.")

        command(alice, "balance", "Bob")

        assertEquals("You do not have permission to do that.", alice.nextText())
    }

    @Test
    fun `pay sends the exact amount and reports success`() {
        command(alice, "pay", "Bob", "100")

        assertEquals("You sent Bob 100.00 TitanCoins.", alice.nextText())
        assertEquals(listOf("pay:${alice.uniqueId}:${bob.uniqueId}:10000"), service.calls)
    }

    @Test
    fun `pay rejects negative zero malformed and offline targets before or during service call`() {
        command(alice, "pay", "Bob", "-100")
        assertEquals("Amount cannot be negative.", alice.nextText())
        assertTrue(service.calls.isEmpty())

        command(alice, "pay", "Bob", "abc")
        assertTrue(alice.nextText().contains("not a valid amount"))

        service.payFailure = EconomyException(EconomyException.Code.INVALID_AMOUNT, "The amount must be greater than zero.")
        command(alice, "pay", "Bob", "0")
        assertEquals("The amount must be greater than zero.", alice.nextText())

        service.payFailure = EconomyException(EconomyException.Code.TARGET_NOT_ONLINE, "That player is not online.")
        command(alice, "pay", "Charlie", "1")
        assertEquals("That player is not online.", alice.nextText())
    }

    @Test
    fun `eco give take and set use the admin service and actor uuid`() {
        command(alice, "eco", "give", "Bob", "1000")
        assertEquals("Gave Bob 1,000.00 TitanCoins. New balance: 1,050.00.", alice.nextText())

        command(alice, "eco", "take", "Bob", "500")
        assertEquals("Took 500.00 TitanCoins from Bob. New balance: 550.00.", alice.nextText())

        command(alice, "eco", "set", "Bob", "0")
        assertEquals("Set Bob's balance to 0.00 TitanCoins.", alice.nextText())

        assertEquals(
            listOf(
                "adminGive:${bob.uniqueId}:100000:${alice.uniqueId}",
                "adminTake:${bob.uniqueId}:50000:${alice.uniqueId}",
                "adminSet:${bob.uniqueId}:0:${alice.uniqueId}",
            ),
            service.calls,
        )
    }

    @Test
    fun `eco rejects negative and malformed amounts and admin permission failures`() {
        command(alice, "eco", "give", "Bob", "-1")
        assertEquals("Amount cannot be negative.", alice.nextText())
        assertTrue(service.calls.isEmpty())

        service.adminFailure = EconomyException(EconomyException.Code.NO_PERMISSION, "You do not have permission to do that.")
        command(alice, "eco", "set", "Bob", "10")
        assertEquals("You do not have permission to do that.", alice.nextText())
    }

    @Test
    fun `balancetop renders ten ordered rows through the central formatter`() {
        service.top = (1..12).map { rank ->
            EconomyBalanceEntry(rank, UUID.randomUUID(), "Player$rank", Money.ofMinor((1000 - rank * 10).toLong()))
        }

        command(alice, "balancetop")

        val message = alice.nextText()
        assertTrue(message.startsWith("TitanSMP BalanceTop\n\n1. Player1"))
        assertTrue(message.contains("2. Player2"))
        assertTrue(message.contains("10. Player10"))
        assertTrue(!message.contains("11. Player11"))
    }

    @Test
    fun `incoming payment event notifies the local receiver only once`() {
        val transactionId = UUID.randomUUID()
        val event = EconomyBalanceChanged(
            EventMetadata(eventType = EconomyBalanceChanged.TYPE, sourceServerId = "smp-01"),
            transactionId,
            bob.uniqueId,
            Money.of("100.00"),
            Money.of("50.00"),
            Money.of("150.00"),
            EconomyReason.PAYMENT,
            alice.uniqueId,
            "Alice",
            null,
            "smp-01",
        )

        module.onBalanceChanged(EventEnvelope(event))
        server.scheduler.performTicks(3)

        assertEquals("You received 100.00 TitanCoins from Alice.", bob.nextText())
    }

    @Test
    fun `outgoing payment debit and admin events do not notify the local player`() {
        val outgoing = EconomyBalanceChanged(
            EventMetadata(eventType = EconomyBalanceChanged.TYPE, sourceServerId = "smp-01"),
            UUID.randomUUID(),
            alice.uniqueId,
            Money.of("100.00"),
            Money.of("1250.50"),
            Money.of("1150.50"),
            EconomyReason.PAYMENT,
            bob.uniqueId,
            "Bob",
            null,
            "smp-01",
        )
        val admin = outgoing.copy(
            playerUuid = alice.uniqueId,
            oldBalance = Money.of("1.00"),
            newBalance = Money.of("2.00"),
            reason = EconomyReason.ADMIN_GIVE,
        )

        module.onBalanceChanged(EventEnvelope(outgoing))
        module.onBalanceChanged(EventEnvelope(admin))
        server.scheduler.performTicks(1)

        alice.assertNoMoreSaid()
    }

    private fun command(sender: CommandSender, name: String, vararg args: String) {
        module.onCommand(sender, object : Command(name) {
            override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean = true
        }, name, args)
        server.scheduler.performTicks(3)
    }

    private fun PlayerMock.nextText(): String = nextMessage() ?: error("Expected a chat message")

    open class TestPaperPlugin : JavaPlugin()

    private class RecordingEventBus : EventBus {
        override fun publish(event: NetworkEvent): CompletionStage<Unit> = CompletableFuture.completedFuture(Unit)
        override fun subscribe(eventType: EventType, listener: EventListener) = EventSubscription {}
        override fun close() = Unit
    }

    private class FakeEconomyService : EconomyService {
        val balances = mutableMapOf<UUID, Money>()
        val calls = mutableListOf<String>()
        var balanceFailure: Throwable? = null
        var payFailure: Throwable? = null
        var adminFailure: Throwable? = null
        var top: List<EconomyBalanceEntry> = emptyList()

        override fun getBalance(player: UUID): CompletionStage<Money> = completed { balances[player] ?: Money.ZERO }

        override fun getBalanceFor(requester: UUID?, target: UUID): CompletionStage<Money> {
            calls += "getBalanceFor:$requester:$target"
            return failure(balanceFailure) { balances[target] ?: Money.ZERO }
        }

        override fun topBalances(requester: UUID?, limit: Int): CompletionStage<List<EconomyBalanceEntry>> = completed { top.take(limit) }

        override fun pay(sender: UUID, receiver: UUID, amount: Money, serverId: String): CompletionStage<EconomyTransfer> {
            calls += "pay:$sender:$receiver:${amount.minorUnits}"
            return failure(payFailure) {
                val senderChange = EconomyChange(UUID.randomUUID(), sender, amount, Money.ZERO, Money.ZERO, EconomyReason.PAYMENT, null, receiver, serverId)
                val receiverChange = EconomyChange(senderChange.transactionId, receiver, amount, Money.ZERO, amount, EconomyReason.PAYMENT, null, sender, serverId)
                EconomyTransfer(senderChange.transactionId, senderChange, receiverChange)
            }
        }

        override fun adminGive(target: UUID, amount: Money, actor: UUID?, serverId: String): CompletionStage<EconomyChange> {
            calls += "adminGive:$target:${amount.minorUnits}:$actor"
            return failure(adminFailure) { change(target, amount, EconomyReason.ADMIN_GIVE, actor, serverId) }
        }

        override fun adminTake(target: UUID, amount: Money, actor: UUID?, serverId: String): CompletionStage<EconomyChange> {
            calls += "adminTake:$target:${amount.minorUnits}:$actor"
            return failure(adminFailure) { change(target, amount, EconomyReason.ADMIN_TAKE, actor, serverId) }
        }

        override fun adminSet(target: UUID, amount: Money, actor: UUID?, serverId: String): CompletionStage<EconomyChange> {
            calls += "adminSet:$target:${amount.minorUnits}:$actor"
            return failure(adminFailure) { change(target, amount, EconomyReason.ADMIN_SET, actor, serverId) }
        }

        override fun deposit(player: UUID, amount: Money, reason: EconomyReason, actor: UUID?, serverId: String) =
            completed { change(player, amount, reason, actor, serverId) }

        override fun withdraw(player: UUID, amount: Money, reason: EconomyReason, actor: UUID?, serverId: String) =
            completed { change(player, amount, reason, actor, serverId) }

        override fun setBalance(player: UUID, amount: Money, reason: EconomyReason, actor: UUID?, serverId: String) =
            completed { change(player, amount, reason, actor, serverId) }

        override fun transfer(sender: UUID, receiver: UUID, amount: Money, reason: EconomyReason, actor: UUID?, serverId: String) =
            completed {
                val outgoing = change(sender, amount, reason, actor, serverId)
                val incoming = change(receiver, amount, reason, actor, serverId)
                EconomyTransfer(outgoing.transactionId, outgoing, incoming)
            }

        private fun change(player: UUID, amount: Money, reason: EconomyReason, actor: UUID?, serverId: String): EconomyChange {
            val before = balances[player] ?: Money.ZERO
            val after = when (reason) {
                EconomyReason.ADMIN_GIVE, EconomyReason.PAYMENT, EconomyReason.AUCTION_HOUSE, EconomyReason.SHOP_PURCHASE, EconomyReason.SHOP_SELL -> before.plus(amount)
                EconomyReason.ADMIN_TAKE -> before.minus(amount)
                EconomyReason.ADMIN_SET -> amount
            }
            balances[player] = after
            return EconomyChange(UUID.randomUUID(), player, amount, before, after, reason, actor, null, serverId)
        }

        private fun <T> completed(value: () -> T): CompletionStage<T> = CompletableFuture.completedFuture(value())
        private fun <T> failure(error: Throwable?, value: () -> T): CompletionStage<T> =
            if (error == null) CompletableFuture.completedFuture(value()) else CompletableFuture.failedFuture(error)
    }

    private class FakePlayerService : PlayerService {
        private val players = mutableMapOf<UUID, NetworkPlayer>()

        fun put(uuid: UUID, username: String, server: String, online: Boolean) {
            val at = Instant.parse("2026-10-20T12:00:00Z")
            players[uuid] = NetworkPlayer(uuid, username, at, at, if (online) server else null, online)
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
}
