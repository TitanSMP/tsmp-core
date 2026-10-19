package de.titansmp.core.player

import de.titansmp.api.player.NetworkPlayer
import java.time.Instant
import java.util.UUID

/** Persistence-only access to player identity records. */
interface PlayerRepository {
    fun findByUuid(uuid: UUID): NetworkPlayer?
    fun findByUsername(username: String): NetworkPlayer?
    fun create(uuid: UUID, username: String, now: Instant): NetworkPlayer
    fun createOrUpdateIdentity(uuid: UUID, username: String, now: Instant): NetworkPlayer
    fun updateUsername(uuid: UUID, username: String): Boolean
    fun updateLastSeen(uuid: UUID, now: Instant): Boolean
    fun setOnline(uuid: UUID, serverId: String, now: Instant): Boolean
    fun setOffline(uuid: UUID, now: Instant): Boolean
    fun setOffline(uuid: UUID, expectedServerId: String, now: Instant): Boolean
}
