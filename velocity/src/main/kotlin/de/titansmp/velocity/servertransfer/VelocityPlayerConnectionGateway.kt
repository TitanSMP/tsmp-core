package de.titansmp.velocity.servertransfer

import com.velocitypowered.api.proxy.ProxyServer
import de.titansmp.core.servertransfer.PlayerConnectionGateway
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * Thin Velocity adapter. It requests an ordinary backend connection, so the existing
 * ServerPreConnectEvent transfer coordinator still performs the coordinated Data Bridge protocol.
 * No second transfer protocol exists here.
 */
class VelocityPlayerConnectionGateway(
    private val proxy: ProxyServer,
) : PlayerConnectionGateway {
    override fun currentServer(playerUuid: UUID): String? =
        proxy.getPlayer(playerUuid).map { player -> player.currentServer.map { it.serverInfo.name }.orElse(null) }
            .orElse(null)

    override fun isOnline(playerUuid: UUID): Boolean = proxy.getPlayer(playerUuid).isPresent

    override fun connect(playerUuid: UUID, targetServerId: String): CompletionStage<Boolean> {
        val player = proxy.getPlayer(playerUuid).orElse(null)
            ?: return CompletableFuture.completedFuture(false)
        if (player.currentServer.map { it.serverInfo.name }.orElse(null) == targetServerId) {
            return CompletableFuture.completedFuture(true)
        }
        val target = proxy.getServer(targetServerId).orElse(null)
            ?: return CompletableFuture.completedFuture(false)
        return try {
            player.createConnectionRequest(target).connect().thenApply { it.isSuccessful }
        } catch (failure: Exception) {
            CompletableFuture.failedFuture(failure)
        }
    }
}
