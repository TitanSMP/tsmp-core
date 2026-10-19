package de.titansmp.core.databridge

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Ignores duplicate or out-of-order state notifications. */
class PlayerStateRevisionTracker {
    private val revisions = ConcurrentHashMap<UUID, Long>()

    /** Seeds a projection from the revision it loaded from MariaDB. */
    fun recordLoaded(playerUuid: UUID, revision: Long) {
        require(revision >= 0)
        revisions.merge(playerUuid, revision, ::maxOf)
    }

    fun observe(playerUuid: UUID, revision: Long): Boolean {
        require(revision > 0)
        while (true) {
            val previous = revisions[playerUuid]
            if (previous != null && revision <= previous) return false
            if (previous == null) {
                if (revisions.putIfAbsent(playerUuid, revision) == null) return true
            } else if (revisions.replace(playerUuid, previous, revision)) return true
        }
    }
}
