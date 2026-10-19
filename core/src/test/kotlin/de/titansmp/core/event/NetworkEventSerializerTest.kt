package de.titansmp.core.event

import de.titansmp.api.event.EventEnvelope
import de.titansmp.api.event.EventMetadata
import de.titansmp.api.event.EventType
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
}
