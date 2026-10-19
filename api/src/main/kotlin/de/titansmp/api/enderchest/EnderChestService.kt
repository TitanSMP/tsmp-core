package de.titansmp.api.enderchest

import java.util.UUID
import java.util.concurrent.CompletionStage

/** Opaque bytes for one Paper-serialized ItemStack; null represents an empty slot. */
class SerializedEnderChestItem(payload: ByteArray) {
    private val bytes = payload.copyOf()
    val payload: ByteArray get() = bytes.copyOf()

    init {
        require(bytes.isNotEmpty()) { "Serialized item payload must not be empty" }
    }

    override fun equals(other: Any?): Boolean = other is SerializedEnderChestItem && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
}

/** Full network-wide storage. The current capacity is always 54 slots regardless of permissions. */
data class EnderChestStorage(
    val playerId: UUID,
    val slots: List<SerializedEnderChestItem?>,
    val revision: Long,
) {
    init {
        require(slots.size == CAPACITY) { "EnderChest storage must contain exactly $CAPACITY slots" }
        require(revision >= 0) { "Revision cannot be negative" }
    }

    companion object {
        const val CAPACITY = 54

        fun empty(playerId: UUID): EnderChestStorage = EnderChestStorage(playerId, List(CAPACITY) { null }, 0)
    }
}

/** Loads and persists one player's complete TitanSMP EnderChest; implementations must not block callers. */
interface EnderChestService {
    /** Loads only while the shared player presence identifies [serverId] as current owner. */
    fun load(playerId: UUID, serverId: String): CompletionStage<EnderChestStorage>

    /** Saves only for the current server (or an offline player) and if the revision is current. */
    fun save(storage: EnderChestStorage, serverId: String): CompletionStage<EnderChestStorage>

    /** Returns the highest configured row permission, defaulting to one row. */
    fun unlockedRows(permissionCheck: EnderChestPermissionCheck): Int
}

fun interface EnderChestPermissionCheck {
    fun hasPermission(permission: String): Boolean
}

class EnderChestStorageConflictException(playerId: UUID) :
    RuntimeException("EnderChest storage for $playerId changed on another server; stale save rejected")

class EnderChestSessionOwnershipException(playerId: UUID, serverId: String) :
    RuntimeException("Server $serverId does not currently own the online player session for $playerId")
