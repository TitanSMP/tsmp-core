package de.titansmp.api.player

import java.time.Instant
import java.util.UUID

/** Network-wide player identity. UUID is the stable identity; username is mutable. */
data class NetworkPlayer(
    val uuid: UUID,
    val username: String,
    val firstJoin: Instant,
    val lastSeen: Instant,
    val currentServer: String?,
    val online: Boolean,
)
