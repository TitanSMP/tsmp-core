package de.titansmp.core.display

import de.titansmp.api.display.PlayerDisplayService
import net.luckperms.api.LuckPerms
import net.luckperms.api.event.group.GroupDataRecalculateEvent
import net.luckperms.api.event.user.UserDataRecalculateEvent
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Refreshes the display cache whenever LuckPerms recalculates rank data.
 *
 * A user recalculation refreshes that player; a group recalculation (for example a prefix or weight
 * change) refreshes every cached player. LuckPerms stays the source of truth; the refresh re-reads the
 * LuckPerms cache and only publishes a change event when the assembled display meta actually differs.
 */
class LuckPermsDisplayListener(
    luckPerms: LuckPerms,
    private val service: PlayerDisplayService,
    private val logger: Logger = Logger.getLogger(LuckPermsDisplayListener::class.java.name),
) : AutoCloseable {
    private val subscriptions = mutableListOf<AutoCloseable>()

    init {
        subscriptions += luckPerms.eventBus.subscribe(UserDataRecalculateEvent::class.java) { event ->
            refresh(event.user.uniqueId)
        }
        subscriptions += luckPerms.eventBus.subscribe(GroupDataRecalculateEvent::class.java) {
            service.cachedPlayers().forEach(::refresh)
        }
    }

    private fun refresh(playerUuid: UUID) {
        try {
            service.refresh(playerUuid).whenComplete { _, failure ->
                if (failure != null) logger.log(Level.FINE, "Display refresh failed for $playerUuid", failure)
            }
        } catch (failure: Throwable) {
            logger.log(Level.FINE, "Display refresh failed for $playerUuid", failure)
        }
    }

    override fun close() {
        subscriptions.forEach { it.close() }
        subscriptions.clear()
    }
}
