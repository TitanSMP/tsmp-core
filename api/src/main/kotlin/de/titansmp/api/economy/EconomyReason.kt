package de.titansmp.api.economy

/**
 * Reason attached to every balance change and audit row.
 *
 * Future features (auction house, admin shop, quests, rewards, ...) add a new enum constant and
 * continue to call the existing [EconomyService] methods. The audit schema stores the enum name as
 * text, so no migration is required merely to record a new reason.
 */
enum class EconomyReason {
    PAYMENT,
    ADMIN_GIVE,
    ADMIN_TAKE,
    ADMIN_SET,
    AUCTION_HOUSE,
}
