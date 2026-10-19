package de.titansmp.core.player

import de.titansmp.api.player.NetworkPlayer
import de.titansmp.api.player.PlayerNotFoundException
import de.titansmp.api.player.PlayerService
import java.time.Clock
import java.util.UUID

class DefaultPlayerService(
    private val repository: PlayerRepository,
    private val clock: Clock = Clock.systemUTC(),
) : PlayerService {
    override fun getPlayer(uuid: UUID): NetworkPlayer = findPlayer(uuid) ?: throw PlayerNotFoundException(uuid)
    override fun findPlayer(uuid: UUID): NetworkPlayer? = repository.findByUuid(uuid)
    override fun findPlayerByName(username: String): NetworkPlayer? = repository.findByUsername(username)

    override fun createOrLoad(uuid: UUID, username: String): NetworkPlayer {
        require(username.isNotBlank()) { "Username must not be blank" }
        require(username.length <= 16) { "Minecraft username must not exceed 16 characters" }
        return repository.createOrUpdateIdentity(uuid, username, clock.instant())
    }

    override fun updateLastSeen(uuid: UUID) { repository.updateLastSeen(uuid, clock.instant()) }

    override fun setOnline(uuid: UUID, serverId: String) {
        require(serverId.isNotBlank()) { "Server identity must not be blank" }
        check(repository.setOnline(uuid, serverId, clock.instant())) { "Player $uuid must exist before setting online" }
    }

    override fun setOffline(uuid: UUID) { repository.setOffline(uuid, clock.instant()) }

    override fun setOffline(uuid: UUID, expectedServerId: String): Boolean =
        repository.setOffline(uuid, expectedServerId, clock.instant())
}
