package de.titansmp.core.moderation

import de.titansmp.api.event.EventBus
import de.titansmp.api.event.EventEnvelope
import de.titansmp.api.event.EventListener
import de.titansmp.api.event.EventSubscription
import de.titansmp.api.event.EventType
import de.titansmp.api.event.NetworkEvent
import de.titansmp.api.moderation.ActivePunishment
import de.titansmp.api.moderation.ModerationActionType
import de.titansmp.api.moderation.ModerationActor
import de.titansmp.api.moderation.ModerationException
import de.titansmp.api.moderation.ModerationPermissionService
import de.titansmp.api.moderation.ModerationTarget
import de.titansmp.api.moderation.PlayerBanned
import de.titansmp.api.moderation.PlayerKicked
import de.titansmp.api.moderation.PlayerMuted
import de.titansmp.api.moderation.PlayerUnbanned
import de.titansmp.api.moderation.PunishmentType
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultModerationServiceTest {
    private val now = Instant.parse("2026-10-19T20:00:00Z")
    private val actorId = UUID.randomUUID()
    private val targetId = UUID.randomUUID()
    private val actor = ModerationActor(actorId, "Moderator")
    private val target = ModerationTarget(targetId, "Steve")
    private val repository = FakeModerationRepository()
    private val bus = RecordingEventBus()
    private val permissionSet = mutableSetOf<Pair<UUID, String>>(actorId to "titansmp.moderation.*")
    private val permissions = ModerationPermissionService { id, node ->
        CompletableFuture.completedFuture(permissionSet.any { (user, granted) -> user == id && (granted == node || granted == "titansmp.moderation.*" && node.startsWith("titansmp.moderation.")) })
    }
    private val service = DefaultModerationService(repository, permissions, bus, java.util.concurrent.Executor(Runnable::run), Clock.fixed(now, ZoneOffset.UTC))

    @Test fun `ban persists active state history and publishes event for offline identity`() {
        val result = service.ban(actor, target, "Cheating", "smp-01").toCompletableFuture().join()
        assertEquals(PunishmentType.BAN, result.type)
        assertEquals(targetId, result.target.uuid)
        assertEquals("Steve", result.target.name)
        assertEquals("BAN", repository.actions.single().action.name)
        assertIs<PlayerBanned>(bus.events.single())
    }

    @Test fun `unban removes active state and adds history without replacing ban history`() {
        service.ban(actor, target, "Cheating", "smp-01").toCompletableFuture().join()
        service.unban(actor, target, null, "smp-02").toCompletableFuture().join()
        assertNull(repository.active[targetId to PunishmentType.BAN])
        assertEquals(listOf(ModerationActionType.BAN, ModerationActionType.UNBAN), repository.actions.map { it.action })
        assertIs<PlayerUnbanned>(bus.events.last())
    }

    @Test fun `timed mute expires while permanent mute remains active`() {
        service.mute(actor, target, Duration.ofMinutes(30), "Spam", "smp-01").toCompletableFuture().join()
        assertTrue(service.isMuted(targetId).toCompletableFuture().join())
        assertFalse(service.isMutedAt(targetId, now.plus(Duration.ofMinutes(30))))

        val other = target.copy(uuid = UUID.randomUUID(), name = "Alex")
        service.mute(actor, other, null, "Spam", "smp-01").toCompletableFuture().join()
        assertNull(repository.active[other.uuid to PunishmentType.MUTE]?.expiresAt)
    }

    @Test fun `kick is logged and published without active punishment`() {
        service.kick(actor, target, "Rule violation", "smp-01").toCompletableFuture().join()
        assertTrue(repository.active.isEmpty())
        assertEquals(ModerationActionType.KICK, repository.actions.single().action)
        assertIs<PlayerKicked>(bus.events.single())
    }

    @Test fun `service blocks self punishment even when caller has permission`() {
        val failure = assertFailsWith<java.util.concurrent.CompletionException> {
            service.ban(actor, ModerationTarget(actorId, actor.name), "reason", "smp-01").toCompletableFuture().join()
        }
        assertEquals(ModerationException.Code.SELF_TARGET, (failure.cause as ModerationException).code)
        assertTrue(repository.actions.isEmpty())
    }

    @Test fun `service blocks immune target even when caller has ban permission`() {
        permissionSet += targetId to "titansmp.moderation.immunity"
        val failure = assertFailsWith<java.util.concurrent.CompletionException> {
            service.ban(actor, target, "reason", "smp-01").toCompletableFuture().join()
        }
        assertEquals(ModerationException.Code.IMMUNE_TARGET, (failure.cause as ModerationException).code)
        assertTrue(repository.actions.isEmpty())
    }

    @Test fun `service checks action permission and uses wildcard-style resolution`() {
        permissionSet.clear()
        val failure = assertFailsWith<java.util.concurrent.CompletionException> {
            service.ban(actor, target, "reason", "smp-01").toCompletableFuture().join()
        }
        assertEquals(ModerationException.Code.NO_PERMISSION, (failure.cause as ModerationException).code)
        assertTrue(repository.actions.isEmpty())
    }

    @Test fun `redis failure does not undo persisted punishment`() {
        bus.failure = IllegalStateException("Redis unavailable")
        service.ban(actor, target, "reason", "smp-01").toCompletableFuture().join()
        assertTrue(repository.active.containsKey(targetId to PunishmentType.BAN))
    }

    private fun DefaultModerationService.isMutedAt(playerId: UUID, instant: Instant): Boolean =
        repository.findActive(playerId, PunishmentType.MUTE, instant) != null

    private data class Action(val action: ModerationActionType)

    private class FakeModerationRepository : ModerationRepository {
        val active = mutableMapOf<Pair<UUID, PunishmentType>, ActivePunishment>()
        val actions = mutableListOf<Action>()
        override fun savePunishment(punishment: ActivePunishment, action: ModerationActionType) { active[punishment.target.uuid to punishment.type] = punishment; actions += Action(action) }
        override fun removePunishment(target: ModerationTarget, type: PunishmentType, actor: ModerationActor, action: ModerationActionType, reason: String?, actionId: UUID, at: Instant, serverId: String): Boolean {
            val existing = active[target.uuid to type] ?: return false
            val expiry = existing.expiresAt
            if (expiry != null && expiry <= at) return false
            active.remove(target.uuid to type); actions += Action(action); return true
        }
        override fun recordKick(target: ModerationTarget, actor: ModerationActor, reason: String, actionId: UUID, at: Instant, serverId: String) { actions += Action(ModerationActionType.KICK) }
        override fun findActive(targetId: UUID, type: PunishmentType, now: Instant): ActivePunishment? = active[targetId to type]?.takeIf { punishment -> punishment.expiresAt?.let { it > now } ?: true }
    }

    private class RecordingEventBus : EventBus {
        val events = mutableListOf<NetworkEvent>()
        var failure: Throwable? = null
        override fun publish(event: NetworkEvent): CompletionStage<Unit> { events += event; return failure?.let(CompletableFuture<Unit>::failedFuture) ?: CompletableFuture.completedFuture(Unit) }
        override fun subscribe(eventType: EventType, listener: EventListener) = EventSubscription {}
        override fun close() = Unit
    }
}

class ModerationDurationParserTest {
    @Test fun `parses supported duration units`() {
        assertEquals(Duration.ofSeconds(30), ModerationDurationParser.parse("30s"))
        assertEquals(Duration.ofMinutes(10), ModerationDurationParser.parse("10m"))
        assertEquals(Duration.ofHours(2), ModerationDurationParser.parse("2h"))
        assertEquals(Duration.ofDays(7), ModerationDurationParser.parse("7d"))
        assertEquals(Duration.ofDays(7), ModerationDurationParser.parse("1w"))
        assertNull(ModerationDurationParser.parse(null))
    }

    @Test fun `rejects malformed and overflowing duration`() {
        listOf("abc", "0s", "2q", "9999999999999999999999d").forEach { value ->
            assertFailsWith<InvalidModerationDurationException> { ModerationDurationParser.parse(value) }
        }
    }
}
