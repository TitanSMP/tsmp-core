package de.titansmp.core.display

import de.titansmp.api.display.DisplayProvider
import de.titansmp.api.display.DisplaySuffixProvider
import de.titansmp.api.display.PlayerDisplayMeta
import de.titansmp.api.display.PlayerDisplayMetaChanged
import de.titansmp.api.display.PlayerDisplayService
import de.titansmp.api.event.EventBus
import de.titansmp.api.event.EventEnvelope
import de.titansmp.api.event.EventListener
import de.titansmp.api.event.EventMetadata
import de.titansmp.api.event.EventSubscription
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Default [PlayerDisplayService]: combines the LuckPerms [DisplayProvider] with optional clan suffix
 * providers, caches the result per UUID, and mirrors [PlayerDisplayMetaChanged] events across servers.
 *
 * The cache is filled from LuckPerms cached data, so a TAB update never triggers a fresh permission
 * lookup on its own. The LuckPerms-backed listener refreshes entries when rank data is recalculated.
 */
class DefaultPlayerDisplayService(
    private val provider: DisplayProvider,
    private val suffixProviders: List<DisplaySuffixProvider> = emptyList(),
    private val eventBus: EventBus? = null,
    private val serverId: String = "unknown",
    private val logger: Logger = Logger.getLogger(DefaultPlayerDisplayService::class.java.name),
) : PlayerDisplayService, AutoCloseable {
    private val cache = ConcurrentHashMap<UUID, PlayerDisplayMeta>()
    private val subscription: EventSubscription? =
        eventBus?.subscribe(PlayerDisplayMetaChanged.TYPE, EventListener(::onRemoteChanged))

    override fun displayMeta(playerUuid: UUID): CompletionStage<PlayerDisplayMeta> {
        cache[playerUuid]?.let { return CompletableFuture.completedFuture(it) }
        return resolve(playerUuid).thenApply { meta -> cache[playerUuid] = meta; meta }
    }

    override fun cachedMeta(playerUuid: UUID): PlayerDisplayMeta? = cache[playerUuid]

    override fun cachedPlayers(): Set<UUID> = cache.keys.toSet()

    override fun refresh(playerUuid: UUID): CompletionStage<PlayerDisplayMeta> = resolve(playerUuid).thenApply { meta ->
        val previous = cache.put(playerUuid, meta)
        if (previous != meta) publish(playerUuid, meta)
        meta
    }

    override fun invalidate(playerUuid: UUID) {
        cache.remove(playerUuid)
    }

    /** Resolves base meta from the provider, then layers every clan suffix provider on top. */
    private fun resolve(playerUuid: UUID): CompletionStage<PlayerDisplayMeta> {
        var stage: CompletionStage<PlayerDisplayMeta> = provider.displayMeta(playerUuid)
        suffixProviders.forEach { suffixProvider ->
            stage = stage.thenCompose { meta ->
                suffixProvider.clanSuffix(playerUuid).thenApply { suffix ->
                    meta.copy(suffix = suffix?.takeIf { it.isNotBlank() } ?: meta.suffix)
                }
            }
        }
        return stage.thenApply { meta -> if (meta.playerUuid == playerUuid) meta else meta.copy(playerUuid = playerUuid) }
    }

    /** Applies display meta published by another server. The local server's own echo is ignored. */
    private fun onRemoteChanged(envelope: EventEnvelope) {
        val event = envelope.event as? PlayerDisplayMetaChanged ?: return
        if (event.metadata.sourceServerId == serverId) return
        cache[event.playerUuid] = event.displayMeta
    }

    private fun publish(playerUuid: UUID, meta: PlayerDisplayMeta) {
        val bus = eventBus ?: return
        val event = PlayerDisplayMetaChanged(
            EventMetadata(eventType = PlayerDisplayMetaChanged.TYPE, sourceServerId = serverId),
            playerUuid,
            meta,
        )
        try {
            bus.publish(event).whenComplete { _, failure ->
                if (failure != null) logger.log(Level.WARNING, "Could not publish display meta change for $playerUuid", failure)
            }
        } catch (failure: Throwable) {
            logger.log(Level.WARNING, "Could not publish display meta change for $playerUuid", failure)
        }
    }

    override fun close() {
        subscription?.close()
        cache.clear()
    }
}
