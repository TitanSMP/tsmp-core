package de.titansmp.core.event

import de.titansmp.api.event.EventEnvelope
import de.titansmp.api.event.EventMetadata
import de.titansmp.api.event.EventType
import de.titansmp.api.friends.*
import de.titansmp.api.clans.*
import de.titansmp.api.teleport.*
import de.titansmp.api.auctionhouse.*
import de.titansmp.api.shop.ShopCategoryStateChanged
import de.titansmp.api.databridge.PlayerStateUpdated
import de.titansmp.api.databridge.PlayerTransferPrepare
import de.titansmp.api.databridge.PlayerTransferReady
import de.titansmp.api.databridge.PlayerTransferFailed
import de.titansmp.api.databridge.PlayerTransferCompleted
import de.titansmp.api.databridge.PlayerTransferTargetPrepared
import de.titansmp.api.player.event.PlayerConnected
import de.titansmp.api.player.event.PlayerDisconnected
import de.titansmp.api.player.event.PlayerServerChanged
import de.titansmp.api.moderation.PlayerBanned
import de.titansmp.api.moderation.PlayerUnbanned
import de.titansmp.api.moderation.PlayerMuted
import de.titansmp.api.moderation.PlayerUnmuted
import de.titansmp.api.moderation.PlayerKicked
import de.titansmp.api.chat.ChatMessage
import de.titansmp.api.chat.ChatMessageSource
import de.titansmp.api.chat.NetworkChatMessage
import de.titansmp.api.display.PlayerDisplayMeta
import de.titansmp.api.display.PlayerDisplayMetaChanged
import de.titansmp.api.discord.DiscordAccountUnlinked
import de.titansmp.api.economy.EconomyBalanceChanged
import de.titansmp.api.economy.EconomyReason
import de.titansmp.api.economy.Money
import de.titansmp.api.servertransfer.ServerQueueLeaveReason
import de.titansmp.api.servertransfer.ServerQueueLeft
import de.titansmp.api.servertransfer.ServerQueueTransferCompleted
import de.titansmp.api.servertransfer.ServerQueueTransferFailed
import de.titansmp.api.servertransfer.ServerQueueTransferStarted
import de.titansmp.api.servertransfer.ServerQueueUpdated
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NetworkEventSerializerTest {
    private val serializer = NetworkEventSerializer()
    private val metadata = EventMetadata(
        eventId = UUID.fromString("d08f85fb-48d8-4adf-aeb7-92a476ca93fe"),
        eventType = PlayerConnected.TYPE,
        timestamp = Instant.parse("2026-08-01T12:30:45.123Z"),
        sourceServerId = "smp-01",
    )

    @Test
    fun `connected event envelope round trips metadata and payload`() {
        val event = PlayerConnected(metadata, UUID.randomUUID(), "TitanUser", "smp-01")
        val decoded = serializer.deserialize(serializer.serialize(EventEnvelope(event)))

        assertEquals(event, decoded.event)
        assertEquals(metadata.eventId, decoded.metadata.eventId)
        assertEquals(metadata.eventType, decoded.metadata.eventType)
        assertEquals(metadata.timestamp, decoded.metadata.timestamp)
        assertEquals(metadata.sourceServerId, decoded.metadata.sourceServerId)
    }

    @Test
    fun `friend events round trip`() {
        val alice = UUID.randomUUID()
        val bob = UUID.randomUUID()
        val events = listOf(
            FriendRequestCreated(metadata.copy(eventType = FriendRequestCreated.TYPE), alice, bob, "Alice"),
            FriendRequestAccepted(metadata.copy(eventType = FriendRequestAccepted.TYPE), bob, alice),
            FriendRequestRejected(metadata.copy(eventType = FriendRequestRejected.TYPE), bob, alice),
            FriendRemoved(metadata.copy(eventType = FriendRemoved.TYPE), alice, bob),
            FriendBlocked(metadata.copy(eventType = FriendBlocked.TYPE), alice, bob),
            FriendUnblocked(metadata.copy(eventType = FriendUnblocked.TYPE), alice, bob),
        )
        events.forEach { assertEquals(it, serializer.deserialize(serializer.serialize(EventEnvelope(it))).event) }
    }

    @Test
    fun `clan events and chat round trip`() {
        val clan = UUID.randomUUID(); val alice = UUID.randomUUID(); val bob = UUID.randomUUID()
        ClanEventAction.entries.forEach { action ->
            val type=ClanChanged.TYPES.getValue(action)
            val event=ClanChanged(metadata.copy(eventType=type),action,clan,alice,bob,ClanRole.MODERATOR,"Example","EX",listOf(alice,bob))
            assertEquals(event,serializer.deserialize(serializer.serialize(EventEnvelope(event))).event)
        }
        val chat=ClanChatMessage(metadata.copy(eventType=ClanChatMessage.TYPE),clan,"EX",ClanColorMode.RAINBOW,"blue",alice,"Alice","Hello")
        assertEquals(chat,serializer.deserialize(serializer.serialize(EventEnvelope(chat))).event)
    }

    @Test fun `teleport transfer and request events round trip`() {
        val player=UUID.randomUUID();val target=UUID.randomUUID();val operation=UUID.randomUUID()
        val events=listOf(
            TeleportTransferRequested(metadata.copy(eventType=TeleportTransferRequested.TYPE),operation,player,"survival-02",player,TeleportCooldownCategory.HOME,"base"),
            TeleportTransferRequested(metadata.copy(eventType=TeleportTransferRequested.TYPE),UUID.randomUUID(),player,null,player,TeleportCooldownCategory.RTP,null,"survival"),
            TeleportRequestCreated(metadata.copy(eventType=TeleportRequestCreated.TYPE),UUID.randomUUID(),player,target,"Alice",TeleportDirection.TARGET_TO_REQUESTER,Instant.parse("2026-08-01T12:31:45Z")),
            TeleportRequestResolved(metadata.copy(eventType=TeleportRequestResolved.TYPE),UUID.randomUUID(),player,target,true),
            TeleportLocationRequested(metadata.copy(eventType=TeleportLocationRequested.TYPE),UUID.randomUUID(),player,target),
        )
        events.forEach { assertEquals(it,serializer.deserialize(serializer.serialize(EventEnvelope(it))).event) }
    }

    @Test
    fun `all player presence payload types round trip`() {
        val playerId = UUID.randomUUID()
        val events = listOf(
            PlayerConnected(metadata, playerId, "TitanUser", "smp-01"),
            PlayerDisconnected(metadata.copy(eventType = PlayerDisconnected.TYPE), playerId, "smp-01"),
            PlayerServerChanged(metadata.copy(eventType = PlayerServerChanged.TYPE), playerId, "smp-01", "lobby-01"),
        )
        events.forEach { assertEquals(it, serializer.deserialize(serializer.serialize(EventEnvelope(it))).event) }
    }

    @Test
    fun `player state update event round trips uuid and revision only`() {
        val event = PlayerStateUpdated(metadata.copy(eventType = PlayerStateUpdated.TYPE), UUID.randomUUID(), 42)
        val decoded = serializer.deserialize(serializer.serialize(EventEnvelope(event))).event
        assertEquals(event, decoded)
    }

    @Test fun `transfer lifecycle events round trip without platform types`() {
        val player = UUID.randomUUID()
        val transferId = UUID.randomUUID()
        val timestamp = Instant.parse("2026-10-19T12:00:00Z")
        val events = listOf(
            PlayerTransferPrepare(metadata.copy(eventType = PlayerTransferPrepare.TYPE), transferId, player, "smp-01", "smp-02", timestamp),
            PlayerTransferReady(metadata.copy(eventType = PlayerTransferReady.TYPE), transferId, player, "smp-01", "smp-02", 42, timestamp),
            PlayerTransferTargetPrepared(metadata.copy(eventType = PlayerTransferTargetPrepared.TYPE), transferId, player, "smp-01", "smp-02", timestamp),
            PlayerTransferFailed(metadata.copy(eventType = PlayerTransferFailed.TYPE), transferId, player, "smp-01", "smp-02", "timeout", timestamp),
            PlayerTransferCompleted(metadata.copy(eventType = PlayerTransferCompleted.TYPE), transferId, player, "smp-01", "smp-02", 43, timestamp),
        )
        events.forEach { assertEquals(it, serializer.deserialize(serializer.serialize(EventEnvelope(it))).event) }
    }

    @Test fun `moderation events round trip nullable actor and expiration fields`() {
        val target = UUID.randomUUID()
        val actor = UUID.randomUUID()
        val action = UUID.randomUUID()
        val at = Instant.parse("2026-10-19T12:00:00Z")
        val events = listOf(
            PlayerBanned(metadata.copy(eventType = PlayerBanned.TYPE), target, "Steve", action, actor, "Moderator", "Cheating", at, null, "smp-01"),
            PlayerUnbanned(metadata.copy(eventType = PlayerUnbanned.TYPE), target, "Steve", action, null, "CONSOLE", null, at, "smp-01"),
            PlayerMuted(metadata.copy(eventType = PlayerMuted.TYPE), target, "Steve", action, actor, "Moderator", "Spam", at, at.plusSeconds(600), "smp-01"),
            PlayerUnmuted(metadata.copy(eventType = PlayerUnmuted.TYPE), target, "Steve", action, actor, "Moderator", null, at, "smp-01"),
            PlayerKicked(metadata.copy(eventType = PlayerKicked.TYPE), target, "Steve", action, actor, "Moderator", "Rule violation", at, "smp-01"),
        )
        events.forEach { assertEquals(it, serializer.deserialize(serializer.serialize(EventEnvelope(it))).event) }
    }

    @Test
    fun `unknown event type is rejected explicitly`() {
        val json = """{"schemaVersion":1,"eventId":"${metadata.eventId}","eventType":"player.future","timestamp":"${metadata.timestamp}","sourceServerId":"smp-01","payload":{}}"""
        val failure = assertFailsWith<UnknownEventTypeException> { serializer.deserialize(json) }
        assertEquals(EventType("player.future"), failure.eventType)
    }

    @Test
    fun `unknown schema version is rejected`() {
        assertFailsWith<IllegalArgumentException> { serializer.deserialize("""{"schemaVersion":99}""") }
    }

    @Test fun `chat message sources and nullable sender round trip`() {
        val chatEvents = ChatMessageSource.entries.mapIndexed { index, source ->
            val message = ChatMessage(UUID.randomUUID(), if (source == ChatMessageSource.SYSTEM) null else UUID.randomUUID(), "Sender-$index", "Hello $source", "smp-01", metadata.timestamp.plusSeconds(index.toLong()), source)
            NetworkChatMessage(metadata.copy(eventType = NetworkChatMessage.TYPE), message)
        }
        chatEvents.forEach { event ->
            assertEquals(event, serializer.deserialize(serializer.serialize(EventEnvelope(event))).event)
        }
    }

    @Test fun `chat prefix round trips and defaults to empty`() {
        val prefixed = ChatMessage(UUID.randomUUID(), UUID.randomUUID(), "Henry", "Hallo", "smp-01", metadata.timestamp, ChatMessageSource.MINECRAFT, "[Admin]")
        val event = NetworkChatMessage(metadata.copy(eventType = NetworkChatMessage.TYPE), prefixed)
        assertEquals(event, serializer.deserialize(serializer.serialize(EventEnvelope(event))).event)

        val withoutPrefix = ChatMessage(UUID.randomUUID(), UUID.randomUUID(), "Henry", "Hallo", "smp-01", metadata.timestamp, ChatMessageSource.MINECRAFT)
        assertEquals("", withoutPrefix.prefix)
    }

    @Test fun `display meta change event round trips nullable suffix and weight`() {
        val player = UUID.randomUUID()
        val events = listOf(
            PlayerDisplayMetaChanged(metadata.copy(eventType = PlayerDisplayMetaChanged.TYPE), player, PlayerDisplayMeta(player, "[Admin]", null, 100)),
            PlayerDisplayMetaChanged(metadata.copy(eventType = PlayerDisplayMetaChanged.TYPE), player, PlayerDisplayMeta(player, "", "[TITAN]", 0)),
        )
        events.forEach { assertEquals(it, serializer.deserialize(serializer.serialize(EventEnvelope(it))).event) }
    }

    @Test fun `economy balance change round trips money reason source and transaction id`() {
        val transactionId = UUID.randomUUID()
        val player = UUID.randomUUID()
        val counterparty = UUID.randomUUID()
        val actor = UUID.randomUUID()
        val events = listOf(
            EconomyBalanceChanged(
                metadata.copy(eventType = EconomyBalanceChanged.TYPE),
                transactionId,
                player,
                Money.of("100.50"),
                Money.of("1000.00"),
                Money.of("1100.50"),
                EconomyReason.PAYMENT,
                counterparty,
                "Henry",
                null,
                "smp-01",
            ),
            EconomyBalanceChanged(
                metadata.copy(eventType = EconomyBalanceChanged.TYPE),
                transactionId,
                counterparty,
                Money.of("100.50"),
                Money.of("10.00"),
                Money.of("110.50"),
                EconomyReason.ADMIN_GIVE,
                null,
                null,
                actor,
                "smp-02",
            ),
        )
        events.forEach { event ->
            val decoded = serializer.deserialize(serializer.serialize(EventEnvelope(event))).event
            assertEquals(event, decoded)
        }
    }

    @Test fun `server queue events round trip without platform types`() {
        val player = UUID.randomUUID()
        val reservation = UUID.randomUUID()
        val events = listOf(
            ServerQueueUpdated(metadata.copy(eventType = ServerQueueUpdated.TYPE), player, "lobby", 5, 20, 2),
            ServerQueueLeft(metadata.copy(eventType = ServerQueueLeft.TYPE), player, "lobby", ServerQueueLeaveReason.TRANSFERRED),
            ServerQueueTransferStarted(metadata.copy(eventType = ServerQueueTransferStarted.TYPE), player, "lobby", "lobby-02", reservation),
            ServerQueueTransferCompleted(metadata.copy(eventType = ServerQueueTransferCompleted.TYPE), player, "lobby", "lobby-02", reservation),
            ServerQueueTransferFailed(metadata.copy(eventType = ServerQueueTransferFailed.TYPE), player, "lobby", "lobby-02", reservation, "connection-rejected"),
        )
        events.forEach { event ->
            assertEquals(event, serializer.deserialize(serializer.serialize(EventEnvelope(event))).event)
        }
    }

    @Test fun `discord account unlink event round trips uuid name and discord id`() {
        val event = DiscordAccountUnlinked(
            metadata.copy(eventType = DiscordAccountUnlinked.TYPE),
            UUID.randomUUID(),
            "Henry",
            424242424242424242L,
        )
        assertEquals(event, serializer.deserialize(serializer.serialize(EventEnvelope(event))).event)
    }

    @Test fun `player stats updated event round trips every statistic`() {
        val player = UUID.randomUUID()
        val stats = de.titansmp.api.stats.PlayerStats(
            playerUuid = player,
            playtimeSeconds = 3_600,
            joins = 7,
            deaths = 3,
            blocksMined = 12_345,
            blocksWalked = 4_200,
            blocksElytra = 900,
            damageTaken = 250,
            damageDealt = 310,
            mobsKilled = 88,
            playersKilled = 4,
            updatedAt = Instant.parse("2026-10-20T10:00:00Z"),
        )
        val event = de.titansmp.api.stats.PlayerStatsUpdated(
            metadata.copy(eventType = de.titansmp.api.stats.PlayerStatsUpdated.TYPE),
            player,
            stats,
        )
        assertEquals(event, serializer.deserialize(serializer.serialize(EventEnvelope(event))).event)
    }

    @Test fun `auction invalidation and item claim events round trip`() {
        val id=UUID.randomUUID();val seller=UUID.randomUUID();val buyer=UUID.randomUUID()
        val events=listOf(
            AuctionChanged(metadata.copy(eventType=AuctionChanged.TYPE),id,seller,buyer,AuctionStatus.SOLD),
            AuctionItemClaimed(metadata.copy(eventType=AuctionItemClaimed.TYPE),UUID.randomUUID(),seller,AuctionItemKind.EXPIRED),
        )
        events.forEach { event->assertEquals(event,serializer.deserialize(serializer.serialize(EventEnvelope(event))).event) }
    }

    @Test fun `shop category state event round trips`() {
        val event=ShopCategoryStateChanged(metadata.copy(eventType=ShopCategoryStateChanged.TYPE),"food",false,UUID.randomUUID(),Instant.parse("2026-10-21T12:00:00Z"))
        assertEquals(event,serializer.deserialize(serializer.serialize(EventEnvelope(event))).event)
    }
}
