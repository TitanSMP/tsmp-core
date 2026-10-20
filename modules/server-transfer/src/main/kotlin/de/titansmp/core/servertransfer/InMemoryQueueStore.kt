package de.titansmp.core.servertransfer

import java.time.Instant
import java.util.TreeSet
import java.util.UUID

/**
 * Deterministic in-memory implementation used by unit tests and local fallback wiring.
 * Production network deployments use [RedisQueueStore] so all proxies share one queue.
 */
class InMemoryQueueStore : QueueStore {
    private val lock = Any()
    private var nextSequence = 0L
    private val queues = mutableMapOf<String, TreeSet<QueueEntry>>()
    private val entriesByPlayer = mutableMapOf<UUID, QueueEntry>()
    private val reservations = mutableMapOf<UUID, QueueReservation>()

    private val ordering = compareBy<QueueEntry> { -it.priority }.thenBy { it.sequence }

    override fun join(playerUuid: UUID, serverType: String, priority: Int, now: Instant): QueueJoinResult = synchronized(lock) {
        val existing = entriesByPlayer[playerUuid]
        if (existing != null && existing.serverType == serverType) {
            return@synchronized resultFor(existing, movedFrom = null)
        }
        val movedFrom = existing?.serverType
        if (existing != null) removeEntry(existing)
        reservations.remove(playerUuid)

        val entry = QueueEntry(playerUuid, serverType, priority, nextSequence++)
        queues.getOrPut(serverType) { TreeSet(ordering) }.add(entry)
        entriesByPlayer[playerUuid] = entry
        resultFor(entry, movedFrom)
    }

    override fun leave(playerUuid: UUID): QueueLeaveResult? = synchronized(lock) {
        val entry = entriesByPlayer[playerUuid] ?: run {
            reservations.remove(playerUuid)
            return@synchronized null
        }
        val hadReservation = reservations.remove(playerUuid) != null
        removeEntry(entry)
        QueueLeaveResult(entry, hadReservation)
    }

    override fun status(playerUuid: UUID, now: Instant): QueueStatusSnapshot? = synchronized(lock) {
        val entry = entriesByPlayer[playerUuid] ?: return@synchronized null
        val queue = queues[entry.serverType] ?: return@synchronized null
        val position = queue.headSet(entry, true).size
        val reservation = reservations[playerUuid]
        QueueStatusSnapshot(entry, position, queue.size, reservation != null && reservation.expiresAt.isAfter(now))
    }

    override fun entries(serverType: String): List<QueueEntry> = synchronized(lock) {
        queues[serverType]?.toList().orEmpty()
    }

    override fun size(serverType: String): Int = synchronized(lock) {
        queues[serverType]?.size ?: 0
    }

    override fun findReservation(playerUuid: UUID, now: Instant): QueueReservation? = synchronized(lock) {
        reservations[playerUuid]
    }

    override fun reserveNext(serverType: String, reservationId: UUID, now: Instant, timeoutMs: Long): QueueReservation? =
        synchronized(lock) {
            require(timeoutMs > 0) { "Reservation timeout must be positive" }
            removeExpiredLocked(now)
            val queue = queues[serverType] ?: return@synchronized null
            val entry = queue.firstOrNull { reservations[it.playerUuid] == null } ?: return@synchronized null
            val reservation = QueueReservation(
                playerUuid = entry.playerUuid,
                serverType = serverType,
                reservationId = reservationId,
                priority = entry.priority,
                sequence = entry.sequence,
                expiresAt = now.plusMillis(timeoutMs),
            )
            reservations[entry.playerUuid] = reservation
            reservation
        }

    override fun renew(reservation: QueueReservation, now: Instant, timeoutMs: Long): Boolean = synchronized(lock) {
        require(timeoutMs > 0) { "Reservation timeout must be positive" }
        val current = reservations[reservation.playerUuid] ?: return@synchronized false
        if (current.reservationId != reservation.reservationId) return@synchronized false
        if (entriesByPlayer[reservation.playerUuid]?.serverType != reservation.serverType) return@synchronized false
        reservations[reservation.playerUuid] = current.copy(expiresAt = now.plusMillis(timeoutMs))
        true
    }

    override fun release(reservation: QueueReservation, removeFromQueue: Boolean): Boolean = synchronized(lock) {
        val current = reservations[reservation.playerUuid] ?: return@synchronized false
        if (current.reservationId != reservation.reservationId) return@synchronized false
        reservations.remove(reservation.playerUuid)
        if (removeFromQueue) {
            entriesByPlayer[reservation.playerUuid]
                ?.takeIf { it.serverType == reservation.serverType && it.sequence == reservation.sequence }
                ?.let(::removeEntry)
        }
        true
    }

    override fun removeExpired(now: Instant): List<QueueReservation> = synchronized(lock) {
        removeExpiredLocked(now)
    }

    private fun removeExpiredLocked(now: Instant): List<QueueReservation> {
        val expired = reservations.values.filter { !it.expiresAt.isAfter(now) }
        expired.forEach { reservations.remove(it.playerUuid) }
        return expired
    }

    private fun resultFor(entry: QueueEntry, movedFrom: String?): QueueJoinResult {
        val queue = queues.getValue(entry.serverType)
        return QueueJoinResult(entry, queue.headSet(entry, true).size, queue.size, movedFrom)
    }

    private fun removeEntry(entry: QueueEntry) {
        val queue = queues[entry.serverType] ?: return
        queue.remove(entry)
        if (queue.isEmpty()) queues.remove(entry.serverType)
        entriesByPlayer.remove(entry.playerUuid)
    }
}
