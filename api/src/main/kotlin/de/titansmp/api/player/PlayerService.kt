package de.titansmp.api.player

import java.util.UUID

/** Operations for the persisted network identity of a player. */
interface PlayerService {
    /** Returns the player or throws [PlayerNotFoundException]. */
    fun getPlayer(uuid: UUID): NetworkPlayer

    fun findPlayer(uuid: UUID): NetworkPlayer?

    fun findPlayerByName(username: String): NetworkPlayer?

    fun createOrLoad(uuid: UUID, username: String): NetworkPlayer

    fun updateLastSeen(uuid: UUID)

    fun setOnline(uuid: UUID, serverId: String)

    fun setOffline(uuid: UUID)

    /** Clears presence only when this server still owns it, protecting server transfers. */
    fun setOffline(uuid: UUID, expectedServerId: String): Boolean
}

class PlayerNotFoundException(uuid: UUID) : NoSuchElementException("Player $uuid was not found")
