package de.titansmp.velocity.servertransfer

import com.velocitypowered.api.proxy.ProxyServer
import de.titansmp.core.config.ServerTransferConfig
import de.titansmp.core.servertransfer.BackendDirectory
import de.titansmp.core.servertransfer.BackendSnapshot
import org.slf4j.Logger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Discovers Velocity's registered backends and joins them with configuration-driven type mapping.
 * Backend names come from Velocity/configuration only, never from TitanSMP source code.
 */
class VelocityBackendDirectory(
    private val proxy: ProxyServer,
    private val config: ServerTransferConfig,
    private val logger: Logger,
) : BackendDirectory {
    private val availability = ConcurrentHashMap<String, Boolean>()

    override fun snapshot(): List<BackendSnapshot> = proxy.allServers.mapNotNull { registered ->
        val name = registered.serverInfo.name
        val type = config.typeForBackend(name) ?: return@mapNotNull null
        val settings = config.typeSettings(type) ?: return@mapNotNull null
        BackendSnapshot(
            name = name,
            serverType = type,
            playerCount = registered.playersConnected.size,
            maxPlayers = settings.maxPlayers,
            // Unknown means "not refreshed yet"; the periodic refresh replaces it with the ping result.
            available = availability[name] ?: true,
            acceptingPlayers = true,
        )
    }

    override fun typeForBackend(backendName: String): String? = config.typeForBackend(backendName)

    /** Refreshes backend availability from Velocity's ping API without blocking the proxy thread. */
    fun refresh(): CompletionStage<Unit> {
        val checks = proxy.allServers.mapNotNull { registered ->
            val name = registered.serverInfo.name
            if (config.typeForBackend(name) == null) return@mapNotNull null
            registered.ping()
                .orTimeout(5, TimeUnit.SECONDS)
                .handle<Unit> { ping, failure ->
                    val available = failure == null && ping != null
                    availability[name] = available
                    if (!available) {
                        logger.debug("Backend {} is unavailable: {}", name, failure?.message ?: "empty ping")
                    }
                    Unit
                }
        }
        return CompletableFuture.allOf(*checks.toTypedArray()).thenApply { Unit }
    }
}
