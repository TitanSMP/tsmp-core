package de.titansmp.core.servertransfer

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueueOrderingTest {
    private val now: Instant = Instant.parse("2026-10-20T12:00:00Z")

    private fun uuid(seed: Int): UUID = UUID.fromString("00000000-0000-0000-0000-${seed.toString().padStart(12, '0')}")

    @Test
    fun `matching member encoding produces priority then fifo order`() {
        val low = QueueMemberCodec.encode(1, 0, uuid(1))
        val samePriorityLater = QueueMemberCodec.encode(1, 1, uuid(2))
        val higherPriority = QueueMemberCodec.encode(3, 2, uuid(3))

        assertTrue(higherPriority < low, "higher priority must sort before lower priority")
        assertTrue(low < samePriorityLater, "same priority must keep FIFO order")

        val decoded = QueueMemberCodec.decode(higherPriority)
        assertEquals(3, decoded.priority)
        assertEquals(2, decoded.sequence)
        assertEquals(uuid(3), decoded.playerUuid)
    }

    @Test
    fun `new player joins the end of their own priority group`() {
        val store = InMemoryQueueStore()
        (1..6).forEach { store.join(uuid(it), "lobby", priority = 1, now = now) }
        (7..10).forEach { store.join(uuid(it), "lobby", priority = 2, now = now) }

        val joiningA = uuid(11)
        store.join(joiningA, "lobby", priority = 2, now = now)

        val order = store.entries("lobby").map { it.playerUuid }
        assertEquals(listOf(uuid(7), uuid(8), uuid(9), uuid(10), joiningA, uuid(1), uuid(2), uuid(3), uuid(4), uuid(5), uuid(6)), order)
    }

    @Test
    fun `higher priority joins ahead of existing lower priority players`() {
        val store = InMemoryQueueStore()
        (1..4).forEach { store.join(uuid(it), "lobby", priority = 1, now = now) }
        (5..8).forEach { store.join(uuid(it), "lobby", priority = 2, now = now) }

        val joiningB = uuid(9)
        store.join(joiningB, "lobby", priority = 3, now = now)

        assertEquals(joiningB, store.entries("lobby").first().playerUuid)
        assertEquals(9, store.size("lobby"))
    }

    @Test
    fun `existing players are not reordered when a new player joins`() {
        val store = InMemoryQueueStore()
        (1..3).forEach { store.join(uuid(it), "lobby", priority = 2, now = now) }
        val before = store.entries("lobby").map { it.playerUuid }

        store.join(uuid(4), "lobby", priority = 1, now = now)

        assertEquals(before, store.entries("lobby").take(3).map { it.playerUuid })
    }

    @Test
    fun `position and total size respect priority ordering`() {
        val store = InMemoryQueueStore()
        (1..3).forEach { store.join(uuid(it), "lobby", priority = 3, now = now) }
        (4..7).forEach { store.join(uuid(it), "lobby", priority = 2, now = now) }
        (8..13).forEach { store.join(uuid(it), "lobby", priority = 1, now = now) }

        val status = assertNotNull(store.status(uuid(5), now))
        assertEquals("lobby", status.entry.serverType)
        // Three priority-3 players, then the priority-2 group: uuid(5) is the second of four.
        assertEquals(5, status.position)
        assertEquals(13, status.size)
    }

    @Test
    fun `duplicate join for the same type does not create a second entry`() {
        val store = InMemoryQueueStore()
        val first = store.join(uuid(1), "lobby", priority = 1, now = now)
        val second = store.join(uuid(1), "lobby", priority = 5, now = now)

        assertEquals(first.position, second.position)
        assertEquals(1, store.size("lobby"))
        assertEquals(1, second.entry.priority)
    }

    @Test
    fun `joining another type moves the player out of the old queue`() {
        val store = InMemoryQueueStore()
        store.join(uuid(1), "lobby", priority = 1, now = now)
        val moved = store.join(uuid(1), "smp", priority = 1, now = now)

        assertEquals("lobby", moved.movedFrom)
        assertEquals(0, store.size("lobby"))
        assertEquals(1, store.size("smp"))
    }

    @Test
    fun `disconnect removal releases the queue entry and reservation`() {
        val store = InMemoryQueueStore()
        store.join(uuid(1), "lobby", priority = 1, now = now)
        store.reserveNext("lobby", UUID.randomUUID(), now, 5_000)

        val left = assertNotNull(store.leave(uuid(1)))

        assertTrue(left.reservationReleased)
        assertEquals(0, store.size("lobby"))
        assertNull(store.status(uuid(1), now))
    }

    @Test
    fun `expired reservation is released and the player becomes eligible again`() {
        val store = InMemoryQueueStore()
        store.join(uuid(1), "lobby", priority = 1, now = now)
        val reservation = assertNotNull(store.reserveNext("lobby", UUID.randomUUID(), now, 1_000))

        assertNull(store.reserveNext("lobby", UUID.randomUUID(), now.plusMillis(500), 1_000))
        val expired = store.removeExpired(now.plusMillis(1_500))
        assertEquals(listOf(reservation.reservationId), expired.map { it.reservationId })

        val reReserved = assertNotNull(store.reserveNext("lobby", UUID.randomUUID(), now.plusMillis(1_500), 1_000))
        assertEquals(uuid(1), reReserved.playerUuid)
    }

    @Test
    fun `reservation timeout does not permanently remove a failed transfer`() {
        val store = InMemoryQueueStore()
        store.join(uuid(1), "lobby", priority = 1, now = now)
        val reservation = assertNotNull(store.reserveNext("lobby", UUID.randomUUID(), now, 5_000))

        assertTrue(store.release(reservation, removeFromQueue = false))

        assertEquals(1, store.size("lobby"))
        assertNotNull(store.status(uuid(1), now))
    }

    @Test
    fun `concurrent reservation never allocates the same player twice`() {
        val store = InMemoryQueueStore()
        val players = (1..50).map(::uuid)
        players.forEach { store.join(it, "lobby", priority = 1, now = now) }

        val allocated = java.util.concurrent.ConcurrentHashMap.newKeySet<UUID>()
        val duplicates = java.util.concurrent.atomic.AtomicInteger()
        val threads = (1..8).map {
            Thread {
                repeat(10) {
                    val reservation = store.reserveNext("lobby", UUID.randomUUID(), now, 5_000)
                    if (reservation != null && !allocated.add(reservation.playerUuid)) {
                        duplicates.incrementAndGet()
                    }
                }
            }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)

        assertEquals(0, duplicates.get())
        assertEquals(50, allocated.size)
    }
}
