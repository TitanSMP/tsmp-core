package de.titansmp.core.event

import de.titansmp.api.event.EventEnvelope
import de.titansmp.api.event.EventMetadata
import de.titansmp.api.event.EventType
import de.titansmp.api.player.event.PlayerConnected
import de.titansmp.api.player.event.PlayerDisconnected
import de.titansmp.api.player.event.PlayerServerChanged
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
