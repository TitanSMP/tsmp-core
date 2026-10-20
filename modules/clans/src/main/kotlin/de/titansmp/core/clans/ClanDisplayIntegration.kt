package de.titansmp.core.clans

import de.titansmp.api.clans.*
import de.titansmp.api.display.DisplaySuffixProvider
import de.titansmp.api.display.PlayerDisplayService
import de.titansmp.api.event.EventBus
import de.titansmp.api.event.EventListener
import de.titansmp.api.event.EventSubscription
import java.util.UUID

class ClanDisplaySuffixProvider(private val clans: ClanService) : DisplaySuffixProvider {
    override fun clanSuffix(playerUuid: UUID) = clans.getClanByMember(playerUuid).thenApply { clan -> clan?.tag?.takeIf(String::isNotBlank)?.let { "[$it]" } }
}

/** Re-resolves cached identities through the existing display service and EventBus. */
class ClanDisplayRefreshListener(eventBus: EventBus, private val display: PlayerDisplayService) : AutoCloseable {
    private val subscriptions: List<EventSubscription> = listOf(
        ClanEventAction.CREATED, ClanEventAction.DISBANDED, ClanEventAction.MEMBER_JOINED,
        ClanEventAction.MEMBER_LEFT, ClanEventAction.MEMBER_KICKED,
    ).map { action -> eventBus.subscribe(ClanChanged.TYPES.getValue(action), EventListener { envelope ->
        val event = envelope.event as ClanChanged
        (event.affectedMembers + listOfNotNull(event.targetUuid)).distinct().forEach { player -> display.refresh(player) }
    }) }
    override fun close() = subscriptions.forEach(EventSubscription::close)
}
