package de.titansmp.core.discord

import de.titansmp.api.chat.ChatMessage
import de.titansmp.api.chat.ChatService
import de.titansmp.api.display.PlayerDisplayMeta
import de.titansmp.api.display.PlayerDisplayService
import de.titansmp.api.discord.DiscordAccountLink
import de.titansmp.api.event.EventBus
import de.titansmp.api.event.EventEnvelope
import de.titansmp.api.event.EventListener
import de.titansmp.api.event.EventSubscription
import de.titansmp.api.event.EventType
import de.titansmp.api.event.NetworkEvent
import de.titansmp.core.discord.link.DiscordLinkRepository
import de.titansmp.core.discord.link.LinkInsertOutcome
import de.titansmp.core.discord.link.LinkTokenConsumption
import de.titansmp.core.discord.role.DiscordRoleNotifier
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Controllable clock shared by the Discord tests. */
internal class MutableClock(private var current: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneId.of("UTC")
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = current
    fun advance(duration: Duration) { current = current.plus(duration) }
    fun set(instant: Instant) { current = instant }
}

/** In-memory event bus that records published events and dispatches them to local subscribers. */
internal class RecordingEventBus : EventBus {
    val events = mutableListOf<NetworkEvent>()
    var failure: Throwable? = null
    private val listeners = mutableMapOf<EventType, MutableList<EventListener>>()

    override fun publish(event: NetworkEvent): CompletionStage<Unit> {
        failure?.let { return CompletableFuture.failedFuture(it) }
        events += event
        listeners[event.metadata.eventType]?.toList()?.forEach { it.onEvent(EventEnvelope(event)) }
        return CompletableFuture.completedFuture(Unit)
    }

    override fun subscribe(eventType: EventType, listener: EventListener): EventSubscription {
        listeners.getOrPut(eventType) { mutableListOf() } += listener
        return EventSubscription { listeners[eventType]?.remove(listener) }
    }

    override fun close() = Unit
}

/** Chat service double that records broadcasts and can simulate a publish failure. */
internal class FakeChatService(var failure: Throwable? = null) : ChatService {
    val broadcasts = mutableListOf<ChatMessage>()

    override fun send(senderUuid: UUID, senderName: String, message: String, serverId: String): CompletionStage<ChatMessage> = error("unused")
    override fun broadcast(message: ChatMessage): CompletionStage<Unit> {
        failure?.let { return CompletableFuture.failedFuture(it) }
        broadcasts += message
        return CompletableFuture.completedFuture(Unit)
    }
    override fun validate(message: ChatMessage) = Unit
    override fun isAllowed(senderUuid: UUID): CompletionStage<Boolean> = CompletableFuture.completedFuture(true)
}

/** Display service double returning a fixed prefix. */
internal class FakeDisplayService(private var prefix: String = "") : PlayerDisplayService {
    override fun displayMeta(playerUuid: UUID): CompletionStage<PlayerDisplayMeta> =
        CompletableFuture.completedFuture(PlayerDisplayMeta(playerUuid, prefix))
    override fun cachedMeta(playerUuid: UUID): PlayerDisplayMeta? = null
    override fun refresh(playerUuid: UUID): CompletionStage<PlayerDisplayMeta> = displayMeta(playerUuid)
    override fun invalidate(playerUuid: UUID) = Unit
    override fun cachedPlayers(): Set<UUID> = emptySet()
    fun setPrefix(value: String) { prefix = value }
}

/** Records verified role grants and revocations. */
internal class FakeRoleNotifier : DiscordRoleNotifier {
    val granted = mutableListOf<Long>()
    val revoked = mutableListOf<Long>()
    var grantFailure: Throwable? = null
    var revokeFailure: Throwable? = null

    override fun grant(discordUserId: Long): CompletionStage<Unit> {
        grantFailure?.let { return CompletableFuture.failedFuture(it) }
        granted += discordUserId
        return CompletableFuture.completedFuture(Unit)
    }

    override fun revoke(discordUserId: Long): CompletionStage<Unit> {
        revokeFailure?.let { return CompletableFuture.failedFuture(it) }
        revoked += discordUserId
        return CompletableFuture.completedFuture(Unit)
    }
}

/** Race-safe in-memory repository mirroring the JDBC semantics. */
internal class InMemoryDiscordLinkRepository : DiscordLinkRepository {
    private data class TokenRow(val playerUuid: UUID, val playerName: String, val expiresAt: Instant, var usedAt: Instant?)

    private val linksByPlayer = LinkedHashMap<UUID, DiscordAccountLink>()
    private val tokens = LinkedHashMap<String, TokenRow>()

    @Synchronized
    override fun findLinkByPlayer(playerUuid: UUID): DiscordAccountLink? = linksByPlayer[playerUuid]

    @Synchronized
    override fun findLinkByDiscord(discordUserId: Long): DiscordAccountLink? =
        linksByPlayer.values.firstOrNull { it.discordUserId == discordUserId }

    @Synchronized
    override fun replaceLinkToken(tokenHash: String, playerUuid: UUID, playerName: String, createdAt: Instant, expiresAt: Instant) {
        tokens.entries.removeIf { it.value.playerUuid == playerUuid && it.value.usedAt == null }
        tokens[tokenHash] = TokenRow(playerUuid, playerName, expiresAt, null)
    }

    @Synchronized
    override fun consumeToken(tokenHash: String, now: Instant): LinkTokenConsumption {
        val row = tokens[tokenHash] ?: return LinkTokenConsumption.NotFound
        row.usedAt?.let { return LinkTokenConsumption.AlreadyUsed }
        if (!row.expiresAt.isAfter(now)) return LinkTokenConsumption.Expired
        row.usedAt = now
        return LinkTokenConsumption.Matched(row.playerUuid, row.playerName)
    }

    @Synchronized
    override fun insertLink(link: DiscordAccountLink): LinkInsertOutcome {
        if (linksByPlayer.containsKey(link.playerUuid)) return LinkInsertOutcome.PLAYER_TAKEN
        if (linksByPlayer.values.any { it.discordUserId == link.discordUserId }) return LinkInsertOutcome.DISCORD_TAKEN
        linksByPlayer[link.playerUuid] = link
        return LinkInsertOutcome.CREATED
    }

    @Synchronized
    override fun deleteLinkByPlayer(playerUuid: UUID): DiscordAccountLink? = linksByPlayer.remove(playerUuid)

    @Synchronized
    override fun deleteLinkByDiscord(discordUserId: Long): DiscordAccountLink? {
        val link = linksByPlayer.values.firstOrNull { it.discordUserId == discordUserId } ?: return null
        linksByPlayer.remove(link.playerUuid)
        return link
    }

    @Synchronized
    fun storedTokenHashes(): Set<String> = tokens.keys.toSet()

    @Synchronized
    fun playerTokens(playerUuid: UUID): Int = tokens.values.count { it.playerUuid == playerUuid }
}
