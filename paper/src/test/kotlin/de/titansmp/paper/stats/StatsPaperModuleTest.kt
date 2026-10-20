package de.titansmp.paper.stats

import de.titansmp.api.event.EventBus
import de.titansmp.api.event.EventEnvelope
import de.titansmp.api.event.EventListener
import de.titansmp.api.event.EventMetadata
import de.titansmp.api.event.EventSubscription
import de.titansmp.api.event.EventType
import de.titansmp.api.event.NetworkEvent
import de.titansmp.api.player.event.PlayerConnected
import de.titansmp.api.stats.PlayerStats
import de.titansmp.api.stats.StatType
import de.titansmp.api.stats.StatsLeaderboardEntry
import de.titansmp.api.stats.StatsService
import de.titansmp.core.config.StatScope
import de.titansmp.core.config.StatTrackingConfig
import de.titansmp.core.config.StatsConfig
import de.titansmp.core.stats.FractionalAccumulator
import de.titansmp.core.stats.PlaytimeTracker
import de.titansmp.core.stats.StatsAggregator
import de.titansmp.core.stats.StatsTrackingPolicy
import org.bukkit.Location
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
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

/**
 * Verifies that the Paper listeners translate Bukkit events into the correct statistics and that no
 * database write happens before the periodic flush. Handler methods are invoked directly, mirroring
 * how the data-bridge Paper tests exercise their listeners deterministically.
 */
class StatsPaperModuleTest {
    private lateinit var server: ServerMock
    private lateinit var plugin: JavaPlugin
    private lateinit var alice: PlayerMock
    private lateinit var service: RecordingStatsService
    private lateinit var eventBus: RecordingEventBus
    private lateinit var module: StatsPaperModule
    private lateinit var aggregator: StatsAggregator
    private lateinit var playtime: PlaytimeTracker
    private lateinit var distance: FractionalAccumulator
    private lateinit var damage: FractionalAccumulator

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        plugin = MockBukkit.load(TestPaperPlugin::class.java)
        alice = server.addPlayer("Alice") as PlayerMock
        service = RecordingStatsService()
        eventBus = RecordingEventBus()
        val policy = policyFor(serverType = "SMP")
        aggregator = StatsAggregator(service)
        playtime = PlaytimeTracker(enabled = policy.tracks(StatType.PLAYTIME), aggregator = aggregator)
        distance = FractionalAccumulator()
        damage = FractionalAccumulator()
        module = StatsPaperModule(
            plugin,
            policy,
            aggregator,
            playtime,
            distance,
            damage,
            eventBus,
            "smp-01",
            flushIntervalMs = 5_000,
        ).also { it.register() }
    }

    @AfterEach
    fun tearDown() {
        module.close()
        MockBukkit.unmock()
    }

    @Test
    fun `block break counts a mined block`() {
        module.onBlockBreak(BlockBreakEvent(block(), alice))

        afterFlush()
        assertEquals(1, service.last(StatType.BLOCKS_MINED))
    }

    @Test
    fun `damage taken counts whole health points`() {
        module.onDamageTaken(damageEvent(alice, 6.0))

        afterFlush()
        assertEquals(3, service.last(StatType.DAMAGE_TAKEN))
    }

    @Test
    fun `damage dealt counts whole health points`() {
        val zombie = spawnZombie()
        module.onDamageDealt(damageByEntityEvent(alice, zombie, 5.0))

        afterFlush()
        assertEquals(2, service.last(StatType.DAMAGE_DEALT))
    }

    @Test
    fun `a mob kill counts a mob kill and not a player kill`() {
        val zombie = spawnZombie()
        zombie.setKiller(alice)
        module.onEntityDeath(EntityDeathEvent(zombie, damageSource(), mutableListOf()))

        afterFlush()
        assertEquals(1, service.last(StatType.MOBS_KILLED))
        assertEquals(0, service.last(StatType.PLAYERS_KILLED))
    }

    @Test
    fun `a player kill counts players killed and not mobs killed`() {
        val victim = server.addPlayer("Victim")
        victim.setKiller(alice)
        module.onPlayerDeath(
            PlayerDeathEvent(victim, damageSource(), mutableListOf(), 0, "died"),
        )
        // The same kill must never also be counted as a mob kill.
        module.onEntityDeath(EntityDeathEvent(victim, damageSource(), mutableListOf()))

        afterFlush()
        assertEquals(1, service.last(StatType.DEATHS))
        assertEquals(1, service.last(StatType.PLAYERS_KILLED))
        assertEquals(0, service.last(StatType.MOBS_KILLED))
    }

    @Test
    fun `death counts a death with no killer`() {
        val victim = server.addPlayer("Lonely")
        module.onPlayerDeath(
            PlayerDeathEvent(victim, damageSource(), mutableListOf(), 0, "died"),
        )

        afterFlush()
        assertEquals(1, service.last(StatType.DEATHS))
        assertEquals(0, service.last(StatType.PLAYERS_KILLED))
    }

    @Test
    fun `walking counts horizontal distance in blocks`() {
        module.onMove(moveEvent(alice, 0.0, 0.0, 3.0, 4.0, gliding = false))

        afterFlush()
        assertEquals(5, service.last(StatType.BLOCKS_WALKED))
        assertEquals(0, service.last(StatType.BLOCKS_ELYTRA))
    }

    @Test
    fun `elytra flight counts elytra distance while gliding`() {
        module.onMove(moveEvent(alice, 0.0, 0.0, 3.0, 4.0, gliding = true))

        afterFlush()
        assertEquals(5, service.last(StatType.BLOCKS_ELYTRA))
        assertEquals(0, service.last(StatType.BLOCKS_WALKED))
    }

    @Test
    fun `a move without a position change is ignored`() {
        module.onMove(moveEvent(alice, 1.0, 1.0, 1.0, 1.0, gliding = false))

        afterFlush()
        assertEquals(0, service.last(StatType.BLOCKS_WALKED))
    }

    @Test
    fun `join counts once through the network presence event and ignores other servers`() {
        module.onJoin(PlayerJoinEvent(alice, ""))
        // A PlayerConnected published by our own server counts the network join.
        eventBus.deliver(PlayerConnected(metadata(PlayerConnected.TYPE), alice.uniqueId, "Alice", "smp-01"))
        // The same event seen from another server must not double count.
        eventBus.deliver(PlayerConnected(metadata(PlayerConnected.TYPE), alice.uniqueId, "Alice", "lobby-01"))

        afterFlush()
        assertEquals(1, service.last(StatType.JOINS))
    }

    @Test
    fun `playtime starts on join and stops on quit`() {
        module.onJoin(PlayerJoinEvent(alice, ""))
        module.onQuit(PlayerQuitEvent(alice, ""))

        afterFlush()
        // The session is shorter than one second, so no whole second is recorded.
        assertEquals(0, service.last(StatType.PLAYTIME))
    }

    @Test
    fun `a lobby server identity does not track smp-only statistics`() {
        module.close()
        val lobbyPolicy = policyFor(serverType = "LOBBY")
        val lobbyAggregator = StatsAggregator(service)
        val lobbyDistance = FractionalAccumulator()
        val lobbyDamage = FractionalAccumulator()
        module = StatsPaperModule(
            plugin,
            lobbyPolicy,
            lobbyAggregator,
            PlaytimeTracker(enabled = false, aggregator = lobbyAggregator),
            lobbyDistance,
            lobbyDamage,
            eventBus,
            "lobby-01",
            5_000,
        ).also { it.register() }

        module.onBlockBreak(BlockBreakEvent(block(), alice))
        module.onDamageTaken(damageEvent(alice, 4.0))
        afterFlush(lobbyAggregator, lobbyDistance, lobbyDamage)

        assertEquals(0, service.last(StatType.BLOCKS_MINED))
        assertEquals(0, service.last(StatType.DAMAGE_TAKEN))
    }

    @Test
    fun `no database write happens per event, only on flush`() {
        module.onBlockBreak(BlockBreakEvent(block(), alice))
        module.onBlockBreak(BlockBreakEvent(block(), alice))

        assertTrue(service.writes.isEmpty())
        afterFlush()
        assertEquals(2, service.last(StatType.BLOCKS_MINED))
    }

    private fun afterFlush(
        activeAggregator: StatsAggregator = aggregator,
        activeDistance: FractionalAccumulator = distance,
        activeDamage: FractionalAccumulator = damage,
    ) {
        activeDistance.drain().forEach { (player, values) -> values.forEach { (type, value) -> activeAggregator.record(player, type, value) } }
        activeDamage.drain().forEach { (player, values) -> values.forEach { (type, value) -> activeAggregator.record(player, type, value) } }
        activeAggregator.flush()
    }

    private fun policyFor(serverType: String): StatsTrackingPolicy {
        val tracking = StatsConfig.defaultTracking() + mapOf(
            StatType.PLAYTIME to StatTrackingConfig(true, StatScope.SERVER_TYPES, setOf("smp")),
            StatType.JOINS to StatTrackingConfig(true, StatScope.NETWORK),
            StatType.DEATHS to StatTrackingConfig(true, StatScope.SERVER_TYPES, setOf("smp")),
            StatType.BLOCKS_MINED to StatTrackingConfig(true, StatScope.SERVER_TYPES, setOf("smp")),
            StatType.BLOCKS_WALKED to StatTrackingConfig(true, StatScope.SERVER_TYPES, setOf("smp")),
            StatType.BLOCKS_ELYTRA to StatTrackingConfig(true, StatScope.SERVER_TYPES, setOf("smp")),
            StatType.DAMAGE_TAKEN to StatTrackingConfig(true, StatScope.SERVER_TYPES, setOf("smp")),
            StatType.DAMAGE_DEALT to StatTrackingConfig(true, StatScope.SERVER_TYPES, setOf("smp")),
            StatType.MOBS_KILLED to StatTrackingConfig(true, StatScope.SERVER_TYPES, setOf("smp")),
            StatType.PLAYERS_KILLED to StatTrackingConfig(true, StatScope.SERVER_TYPES, setOf("smp")),
        )
        return StatsTrackingPolicy(StatsConfig(enabled = true, tracking = tracking), serverType)
    }

    private fun metadata(type: EventType) = EventMetadata(eventType = type, sourceServerId = "smp-01")

    private fun block() = alice.location.world!!.getBlockAt(alice.location)

    private fun spawnZombie(): LivingEntity =
        alice.location.world!!.spawnEntity(alice.location, EntityType.ZOMBIE) as LivingEntity

    private fun moveEvent(player: Player, fromX: Double, fromZ: Double, toX: Double, toZ: Double, gliding: Boolean): PlayerMoveEvent {
        val world = player.location.world!!
        val from = Location(world, fromX, 64.0, fromZ)
        val to = Location(world, toX, 64.0, toZ)
        (player as LivingEntity).isGliding = gliding
        return PlayerMoveEvent(player, from, to)
    }

    private fun damageEvent(entity: org.bukkit.entity.Entity, amount: Double): EntityDamageEvent =
        EntityDamageEvent(entity, EntityDamageEvent.DamageCause.ENTITY_ATTACK, amount)

    private fun damageByEntityEvent(damager: org.bukkit.entity.Entity, entity: org.bukkit.entity.Entity, amount: Double): EntityDamageByEntityEvent =
        EntityDamageByEntityEvent(damager, entity, EntityDamageEvent.DamageCause.ENTITY_ATTACK, amount)

    private fun damageSource(): org.bukkit.damage.DamageSource =
        org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.GENERIC).build()

    open class TestPaperPlugin : JavaPlugin()

    private class RecordingEventBus : EventBus {
        private val listeners = mutableMapOf<EventType, MutableList<EventListener>>()
        override fun publish(event: NetworkEvent): CompletionStage<Unit> = CompletableFuture.completedFuture(Unit)
        override fun subscribe(eventType: EventType, listener: EventListener): EventSubscription {
            listeners.getOrPut(eventType) { mutableListOf() } += listener
            return EventSubscription { listeners[eventType]?.remove(listener) }
        }
        override fun close() = listeners.clear()
        fun deliver(event: NetworkEvent) {
            listeners[event.metadata.eventType]?.toList()?.forEach { it.onEvent(EventEnvelope(event)) }
        }
    }

    private class RecordingStatsService : StatsService {
        val writes = mutableListOf<Pair<StatType, Long>>()
        private val totals = mutableMapOf<UUID, MutableMap<StatType, Long>>()

        override fun increment(playerUuid: UUID, statType: StatType, delta: Long): CompletionStage<PlayerStats> =
            incrementAll(playerUuid, mapOf(statType to delta))

        override fun incrementAll(playerUuid: UUID, deltas: Map<StatType, Long>): CompletionStage<PlayerStats> {
            val perPlayer = totals.getOrPut(playerUuid) { mutableMapOf() }
            deltas.forEach { (type, delta) ->
                writes += type to delta
                perPlayer[type] = (perPlayer[type] ?: 0) + delta
            }
            return CompletableFuture.completedFuture(snapshot(playerUuid))
        }

        override fun getStats(playerUuid: UUID): CompletionStage<PlayerStats> =
            CompletableFuture.completedFuture(snapshot(playerUuid))

        override fun getStats(playerUuid: UUID, statTypes: Collection<StatType>): CompletionStage<PlayerStats> = getStats(playerUuid)

        override fun getTopPlayers(statType: StatType, limit: Int): CompletionStage<List<StatsLeaderboardEntry>> =
            CompletableFuture.completedFuture(emptyList())

        fun last(statType: StatType): Long = totals.values.sumOf { it[statType] ?: 0 }

        private fun snapshot(playerUuid: UUID): PlayerStats {
            val values = totals[playerUuid].orEmpty()
            return PlayerStats(
                playerUuid = playerUuid,
                playtimeSeconds = values[StatType.PLAYTIME] ?: 0,
                joins = values[StatType.JOINS] ?: 0,
                deaths = values[StatType.DEATHS] ?: 0,
                blocksMined = values[StatType.BLOCKS_MINED] ?: 0,
                blocksWalked = values[StatType.BLOCKS_WALKED] ?: 0,
                blocksElytra = values[StatType.BLOCKS_ELYTRA] ?: 0,
                damageTaken = values[StatType.DAMAGE_TAKEN] ?: 0,
                damageDealt = values[StatType.DAMAGE_DEALT] ?: 0,
                mobsKilled = values[StatType.MOBS_KILLED] ?: 0,
                playersKilled = values[StatType.PLAYERS_KILLED] ?: 0,
                updatedAt = Instant.parse("2026-10-20T10:00:00Z"),
            )
        }
    }
}
