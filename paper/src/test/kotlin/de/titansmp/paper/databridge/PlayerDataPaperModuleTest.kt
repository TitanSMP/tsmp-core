package de.titansmp.paper.databridge

import de.titansmp.api.databridge.PlayerDataService
import de.titansmp.api.databridge.PlayerState
import de.titansmp.api.databridge.PlayerStateUpdated
import de.titansmp.api.databridge.PlayerTransferPrepare
import de.titansmp.api.databridge.PlayerTransferReady
import de.titansmp.api.databridge.PlayerTransferCompleted
import de.titansmp.api.databridge.PlayerTransferTargetPrepared
import de.titansmp.api.databridge.PlayerTransferFailed
import de.titansmp.api.databridge.SerializedPlayerItem
import de.titansmp.api.databridge.PotionEffectData
import de.titansmp.api.event.EventBus
import de.titansmp.api.event.EventEnvelope
import de.titansmp.api.event.EventMetadata
import de.titansmp.api.event.EventListener
import de.titansmp.api.event.EventSubscription
import de.titansmp.api.event.EventType
import de.titansmp.api.event.NetworkEvent
import de.titansmp.paper.serialization.PaperItemStackCodec
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import java.util.UUID
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import org.bukkit.advancement.Advancement
import org.bukkit.advancement.AdvancementProgress

class PlayerDataPaperModuleTest {
    private lateinit var server: ServerMock
    private lateinit var plugin: TestPlugin
    private lateinit var service: FakePlayerDataService
    private lateinit var eventBus: RecordingEventBus
    private lateinit var module: PlayerDataPaperModule
    private lateinit var advancementAccess: TestAdvancementAccess

    @BeforeEach fun setUp() {
        server = MockBukkit.mock()
        plugin = MockBukkit.load(TestPlugin::class.java)
        service = FakePlayerDataService()
        eventBus = RecordingEventBus()
        advancementAccess = TestAdvancementAccess()
        module = PlayerDataPaperModule(plugin, service, "smp-01", eventBus, advancementAccess = advancementAccess)
        module.register()
    }

    @AfterEach fun tearDown() {
        if (::module.isInitialized) module.flushAndClose().toCompletableFuture().join()
        MockBukkit.unmock()
    }

    @Test fun `join applies persisted main armor offhand vitals and experience`() {
        val player = server.addPlayer()
        val state = PlayerState(
            player.uniqueId,
            List(36) { if (it == 8) encode(ItemStack(Material.DIAMOND, 4)) else null },
            listOf(encode(ItemStack(Material.IRON_HELMET)), null, null, encode(ItemStack(Material.IRON_BOOTS))),
            encode(ItemStack(Material.SHIELD)), 14.5, 16, .45f, 23, 781, 7,
        )
        service.loaded = CompletableFuture.completedFuture(state)
        assertTrue(plugin.isEnabled)
        assertTrue(player.isOnline)
        module.loadOnJoin(player)
        server.scheduler.performOneTick()

        assertEquals(16, player.foodLevel)
        assertEquals(23, player.level)
        assertEquals(.45f, player.exp)
        assertEquals(781, player.totalExperience)
        assertEquals(14.5, player.health)
        assertEquals(Material.DIAMOND, player.inventory.getItem(8)?.type)
        assertEquals(Material.IRON_HELMET, player.inventory.helmet?.type)
        assertEquals(Material.IRON_BOOTS, player.inventory.boots?.type)
        assertEquals(Material.SHIELD, player.inventory.itemInOffHand.type)
        assertEquals(1, service.loads)
    }

    @Test fun `join replaces target effects and expires effects using captured transfer time`() {
        val player = server.addPlayer()
        val speed = Registry.EFFECT.get(NamespacedKey.minecraft("speed"))!!
        val strength = Registry.EFFECT.get(NamespacedKey.minecraft("strength"))!!
        player.addPotionEffect(PotionEffect(speed, 5_000, 0))
        val state = state(player.uniqueId).copy(
            potionEffects = listOf(
                PotionEffectData("minecraft:speed", 620, 1, true, false, true),
                PotionEffectData("minecraft:strength", 80, 0, false, true, false),
                PotionEffectData("minecraft:regeneration", 100, 0, false, true, true),
            ),
            capturedAt = Instant.now().minusSeconds(6),
        )
        service.loaded = CompletableFuture.completedFuture(state)
        module.loadOnJoin(player)
        server.scheduler.performOneTick()

        assertEquals(1, player.activePotionEffects.size)
        assertTrue(player.hasPotionEffect(speed))
        assertEquals(1, player.getPotionEffect(speed)?.amplifier)
        assertTrue(player.getPotionEffect(speed)!!.duration in 450..620)
        assertTrue(player.getPotionEffect(speed)!!.isAmbient)
        assertTrue(!player.getPotionEffect(speed)!!.hasParticles())
        assertTrue(player.getPotionEffect(speed)!!.hasIcon())
        assertTrue(player.hasPotionEffect(strength).not())
        assertTrue(player.activePotionEffects.none { it.type.key == NamespacedKey.minecraft("regeneration") })
    }

    @Test fun `removed potion effect is absent from the next lifecycle save`() {
        val player = server.addPlayer()
        val state = state(player.uniqueId).copy(potionEffects = listOf(PotionEffectData("minecraft:speed", 1_200, 0, false, true, true)))
        service.loaded = CompletableFuture.completedFuture(state)
        module.loadOnJoin(player)
        server.scheduler.performOneTick()

        player.removePotionEffect(Registry.EFFECT.get(NamespacedKey.minecraft("speed"))!!)
        module.saveOnQuit(player).toCompletableFuture().join()

        assertTrue(service.saves.single().potionEffects.isEmpty())
    }

    @Test fun `effect removed on death remains absent after respawn lifecycle save`() {
        val player = server.addPlayer()
        service.loaded = CompletableFuture.completedFuture(
            state(player.uniqueId).copy(potionEffects = listOf(PotionEffectData("minecraft:speed", 1_200, 0, false, true, true))),
        )
        module.loadOnJoin(player)
        server.scheduler.performOneTick()
        player.removePotionEffect(Registry.EFFECT.get(NamespacedKey.minecraft("speed"))!!)
        module.trackPotionEffectsAfterDeath(
            PlayerDeathEvent(player, DamageSource.builder(DamageType.GENERIC).build(), mutableListOf(), 0, "died"),
        )
        // The post-respawn lifecycle save snapshots the current Paper state, not the old loaded effect list.
        module.saveOnQuit(player).toCompletableFuture().join()

        assertTrue(service.saves.single().potionEffects.isEmpty())
    }

    @Test fun `restoration replaces partial criterion progress instead of merging`() {
        val player = server.addPlayer()
        advancementAccess.awarded += "b"
        service.loaded = CompletableFuture.completedFuture(state(player.uniqueId).copy(advancements = listOf(
            de.titansmp.api.databridge.AdvancementProgressData(advancementAccess.key.toString(), listOf(
                de.titansmp.api.databridge.CriterionProgressData("a", true),
                de.titansmp.api.databridge.CriterionProgressData("b", false),
                de.titansmp.api.databridge.CriterionProgressData("c", false),
            )),
        )))

        module.loadOnJoin(player)
        server.scheduler.performOneTick()
        player.inventory.setItem(0, ItemStack(Material.DIAMOND))
        module.saveOnQuit(player).toCompletableFuture().join()

        assertEquals(setOf("a"), advancementAccess.awarded)
        assertEquals(listOf(true, false, false), service.saves.single().advancements.single().criteria.map { it.completed })
    }

    @Test fun `complete advancement remains canonical without replaying rewards`() {
        val player = server.addPlayer()
        val saved = de.titansmp.api.databridge.AdvancementProgressData(advancementAccess.key.toString(), advancementAccess.criteria.sorted().map {
            de.titansmp.api.databridge.CriterionProgressData(it, true)
        })
        service.loaded = CompletableFuture.completedFuture(state(player.uniqueId).copy(advancements = listOf(saved)))

        module.loadOnJoin(player)
        server.scheduler.performOneTick()
        player.inventory.setItem(0, ItemStack(Material.DIAMOND))
        module.saveOnQuit(player).toCompletableFuture().join()

        assertTrue(advancementAccess.awarded.isEmpty())
        assertEquals(listOf(saved), service.saves.single().advancements)
    }

    @Test fun `unknown advancement key is retained through a target snapshot`() {
        val player = server.addPlayer()
        val unknown = de.titansmp.api.databridge.AdvancementProgressData("someplugin:custom", listOf(
            de.titansmp.api.databridge.CriterionProgressData("first", true),
            de.titansmp.api.databridge.CriterionProgressData("second", false),
        ))
        service.loaded = CompletableFuture.completedFuture(state(player.uniqueId).copy(advancements = listOf(unknown)))

        module.loadOnJoin(player)
        server.scheduler.performOneTick()
        player.inventory.setItem(0, ItemStack(Material.DIAMOND))
        module.saveOnQuit(player).toCompletableFuture().join()

        assertEquals(listOf(unknown), service.saves.single().advancements.filter { it.advancementKey == unknown.advancementKey })
    }

    @Test fun `quit saves changed snapshot and publishes committed revision`() {
        val player = server.addPlayer()
        val baseline = state(player.uniqueId)
        service.loaded = CompletableFuture.completedFuture(baseline)
        module.loadOnJoin(player)
        server.scheduler.performOneTick()
        player.inventory.setItem(0, ItemStack(Material.EMERALD, 5))
        player.foodLevel = 11

        module.saveOnQuit(player).toCompletableFuture().join()

        assertEquals(1, service.saves.size)
        assertEquals(11, service.saves.single().foodLevel)
        assertEquals(SerializedPlayerItem(PaperItemStackCodec.encode(ItemStack(Material.EMERALD, 5))!!), service.saves.single().inventory[0])
        assertTrue(eventBus.events.single() is PlayerStateUpdated)
        assertEquals(8L, (eventBus.events.single() as PlayerStateUpdated).revision)
    }

    @Test fun `new player vanilla defaults are saved on quit`() {
        val player = server.addPlayer()
        player.inventory.setItem(0, ItemStack(Material.DIAMOND, 32))
        player.level = 8
        player.foodLevel = 5
        service.loaded = CompletableFuture.completedFuture(null)
        module.loadOnJoin(player)
        server.scheduler.performOneTick()
        assertNull(player.inventory.getItem(0))
        assertEquals(20, player.foodLevel)
        assertEquals(0, player.level)
        module.saveOnQuit(player).toCompletableFuture().join()

        assertEquals(1, service.saves.size)
        assertNull(service.saves.single().inventory.first())
        assertEquals(20, service.saves.single().foodLevel)
        assertEquals(0, service.saves.single().level)
    }

    @Test fun `experience levels and progress restore at zero ordinary and high levels`() {
        listOf(Triple(0, 0, 0f), Triple(17, 357, .63f), Triple(1000, 500_000, .91f)).forEach { (level, total, progress) ->
            val player = server.addPlayer()
            service.loaded = CompletableFuture.completedFuture(
                PlayerState(player.uniqueId, List(36) { null }, List(4) { null }, null, 20.0, 20, progress, level, total, 5),
            )
            module.loadOnJoin(player)
            server.scheduler.performOneTick()
            assertEquals(level, player.level)
            assertEquals(total, player.totalExperience)
            assertEquals(progress, player.exp)
        }
    }

    @Test fun `shutdown persists online state changes`() {
        val player = server.addPlayer()
        service.loaded = CompletableFuture.completedFuture(state(player.uniqueId))
        module.loadOnJoin(player)
        server.scheduler.performOneTick()
        player.inventory.setItemInOffHand(ItemStack(Material.TORCH))

        module.flushAndClose().toCompletableFuture().join()
        assertEquals(1, service.saves.size)
        assertTrue(service.saves.single().offhand != null)
    }

    @Test fun `transfer target accepts a later MariaDB revision and publishes completion`() {
        val player = server.addPlayer()
        val playerId = player.uniqueId
        val transferId = UUID.randomUUID()
        val now = Instant.now()
        service.loaded = CompletableFuture.completedFuture(state(playerId).withRevision(43))
        eventBus.emit(PlayerTransferPrepare(EventMetadata(eventType = PlayerTransferPrepare.TYPE, sourceServerId = "velocity"), transferId, playerId, "smp-02", "smp-01", now))
        eventBus.emit(PlayerTransferReady(EventMetadata(eventType = PlayerTransferReady.TYPE, sourceServerId = "smp-02"), transferId, playerId, "smp-02", "smp-01", 42, now))
        server.scheduler.performOneTick()

        assertTrue(player.isOnline)
        assertTrue(eventBus.events.any { it is PlayerTransferCompleted && it.stateRevision == 43L })
        assertTrue(eventBus.events.any { it is PlayerTransferTargetPrepared && it.transferId == transferId })
    }

    @Test fun `transfer target rejects a state older than the ready revision`() {
        val player = server.addPlayer()
        val playerId = player.uniqueId
        val transferId = UUID.randomUUID()
        val now = Instant.now()
        service.loaded = CompletableFuture.completedFuture(state(playerId).withRevision(41))
        eventBus.emit(PlayerTransferPrepare(EventMetadata(eventType = PlayerTransferPrepare.TYPE, sourceServerId = "velocity"), transferId, playerId, "smp-02", "smp-01", now))
        eventBus.emit(PlayerTransferReady(EventMetadata(eventType = PlayerTransferReady.TYPE, sourceServerId = "smp-02"), transferId, playerId, "smp-02", "smp-01", 42, now))
        server.scheduler.performOneTick()

        assertTrue(eventBus.events.any { it is PlayerTransferFailed && it.reason == "target-state-below-required-revision" })
        assertTrue(eventBus.events.none { it is PlayerTransferCompleted })
    }

    @Test fun `source save failure publishes failure and does not publish ready`() {
        val player = server.addPlayer()
        service.loaded = CompletableFuture.completedFuture(state(player.uniqueId))
        module.loadOnJoin(player)
        server.scheduler.performOneTick()
        player.foodLevel = 12
        service.saveFailure = IllegalStateException("database unavailable")
        val transferId = UUID.randomUUID()
        val now = Instant.now()
        eventBus.emit(PlayerTransferPrepare(EventMetadata(eventType = PlayerTransferPrepare.TYPE, sourceServerId = "velocity"), transferId, player.uniqueId, "smp-01", "smp-02", now))
        server.scheduler.performOneTick()

        assertTrue(player.isOnline)
        assertEquals(12, player.foodLevel)
        assertTrue(eventBus.events.any { it is PlayerTransferFailed && it.transferId == transferId })
        assertTrue(eventBus.events.none { it is PlayerTransferReady && it.transferId == transferId })
    }

    @Test fun `paper codec preserves complex item metadata including persistent data`() {
        val original = ItemStack(Material.DIAMOND_SWORD, 1)
        original.editMeta { meta ->
            meta.displayName(Component.text("Titan blade"))
            meta.lore(listOf(Component.text("First line"), Component.text("Second line")))
            meta.addEnchant(Enchantment.SHARPNESS, 5, true)
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
            meta.setCustomModelData(9042)
            meta.persistentDataContainer.set(NamespacedKey("titansmp", "quality"), PersistentDataType.STRING, "legendary")
        }
        val decoded = PaperItemStackCodec.decode(PaperItemStackCodec.encode(original))!!

        assertEquals(original.type, decoded.type)
        assertEquals(original.amount, decoded.amount)
        assertEquals(original.itemMeta, decoded.itemMeta)
        assertNull(PaperItemStackCodec.encode(ItemStack(Material.AIR)))
    }

    private fun state(uuid: UUID) = PlayerState(uuid, List(36) { null }, List(4) { null }, null, 20.0, 20, 0f, 0, 0, 7)
    private fun encode(item: ItemStack) = SerializedPlayerItem(PaperItemStackCodec.encode(item)!!)

    open class TestPlugin : JavaPlugin()

    private class FakePlayerDataService : PlayerDataService {
        var loaded: CompletableFuture<PlayerState?> = CompletableFuture.completedFuture(null)
        val saves = mutableListOf<PlayerState>()
        var loads = 0
        var saveFailure: Throwable? = null
        override fun load(playerUuid: UUID, serverId: String): CompletionStage<PlayerState?> { loads++; return loaded }
        override fun save(state: PlayerState, serverId: String): CompletionStage<PlayerState> {
            saves += state
            saveFailure?.let { return CompletableFuture.failedFuture(it) }
            return CompletableFuture.completedFuture(state.withRevision(state.revision + 1))
        }
    }

    private class RecordingEventBus : EventBus {
        val events = mutableListOf<NetworkEvent>()
        private val listeners = mutableMapOf<EventType, MutableList<EventListener>>()
        override fun publish(event: NetworkEvent): CompletionStage<Unit> { events += event; return CompletableFuture.completedFuture(Unit) }
        override fun subscribe(eventType: EventType, listener: EventListener): EventSubscription {
            listeners.getOrPut(eventType) { mutableListOf() } += listener
            return EventSubscription { listeners[eventType]?.remove(listener) }
        }
        fun emit(event: NetworkEvent) { listeners[event.metadata.eventType]?.toList()?.forEach { it.onEvent(EventEnvelope(event)) } }
        override fun close() = Unit
    }

}
