package de.titansmp.core.discord.role

import de.titansmp.api.discord.DiscordAccountUnlinked
import de.titansmp.api.event.EventMetadata
import de.titansmp.core.discord.FakeRoleNotifier
import de.titansmp.core.discord.RecordingEventBus
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiscordUnlinkRoleListenerTest {
    private val bus = RecordingEventBus()
    private val roles = FakeRoleNotifier()

    @Test
    fun `an unlink from another server revokes the verified role`() {
        DiscordUnlinkRoleListener(bus, roles, serverId = "discord").use {
            bus.publish(event(sourceServerId = "smp-01", discordUserId = 42L)).toCompletableFuture().join()
        }
        assertEquals(listOf(42L), roles.revoked)
    }

    @Test
    fun `the bot ignores the echo of its own unlink event`() {
        DiscordUnlinkRoleListener(bus, roles, serverId = "discord").use {
            bus.publish(event(sourceServerId = "discord", discordUserId = 42L)).toCompletableFuture().join()
        }
        assertTrue(roles.revoked.isEmpty())
    }

    @Test
    fun `a role removal failure never propagates`() {
        roles.revokeFailure = IllegalStateException("Discord unavailable")
        DiscordUnlinkRoleListener(bus, roles, serverId = "discord").use {
            bus.publish(event(sourceServerId = "smp-01", discordUserId = 43L)).toCompletableFuture().join()
        }
    }

    private fun event(sourceServerId: String, discordUserId: Long) = DiscordAccountUnlinked(
        EventMetadata(eventType = DiscordAccountUnlinked.TYPE, sourceServerId = sourceServerId),
        UUID.randomUUID(),
        "Henry",
        discordUserId,
    )
}
