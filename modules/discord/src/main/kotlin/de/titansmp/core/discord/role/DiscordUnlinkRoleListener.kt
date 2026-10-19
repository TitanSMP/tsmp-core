package de.titansmp.core.discord.role

import de.titansmp.api.discord.DiscordAccountUnlinked
import de.titansmp.api.event.EventBus
import de.titansmp.api.event.EventEnvelope
import de.titansmp.api.event.EventListener
import de.titansmp.api.event.EventSubscription
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Removes the verified role when a link is removed on another runtime.
 *
 * A Minecraft `/unlink` publishes [DiscordAccountUnlinked]. The bot subscribes here and revokes the
 * role best-effort: if Discord is unreachable the DB state stays consistent and only a warning is
 * logged, with no retry loop. The bot's own unlink publishes the same event; the origin server id check
 * skips that echo because the bot already revoked the role directly in its command handler.
 */
class DiscordUnlinkRoleListener(
    eventBus: EventBus,
    private val roles: DiscordRoleNotifier,
    private val serverId: String,
    private val logger: Logger = Logger.getLogger(DiscordUnlinkRoleListener::class.java.name),
) : AutoCloseable {
    private val subscription: EventSubscription = eventBus.subscribe(DiscordAccountUnlinked.TYPE, EventListener(::onEvent))

    fun onEvent(envelope: EventEnvelope) {
        val event = envelope.event as? DiscordAccountUnlinked ?: return
        if (event.metadata.sourceServerId == serverId) return
        try {
            roles.revoke(event.discordUserId).whenComplete { _, failure ->
                if (failure != null) logger.log(Level.WARNING, "The verified role could not be removed after an account unlink", failure)
            }
        } catch (failure: Throwable) {
            logger.log(Level.WARNING, "The verified role could not be removed after an account unlink", failure)
        }
    }

    override fun close() = subscription.close()
}
