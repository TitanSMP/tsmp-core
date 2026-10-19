package de.titansmp.core.databridge

import de.titansmp.api.databridge.PlayerState
import de.titansmp.api.databridge.PlayerStateConflictException
import de.titansmp.api.databridge.PlayerStateOwnershipException
import de.titansmp.api.databridge.SerializedPlayerItem
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import javax.sql.DataSource

/** Stores the complete Phase 1 state in one revision-checked MariaDB row. */
class JdbcPlayerStateRepository(private val dataSource: DataSource) : PlayerStateRepository {
    override fun load(playerUuid: UUID, serverId: String): PlayerState? = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT state_payload, revision FROM player_state WHERE player_uuid = ? AND EXISTS (" +
                "SELECT 1 FROM players WHERE players.uuid = player_state.player_uuid AND online = TRUE AND current_server = ?)",
        ).use { statement ->
            statement.setBytes(1, playerUuid.bytes()); statement.setString(2, serverId)
            statement.executeQuery().use { result ->
                if (result.next()) StateBlob.decode(playerUuid, result.getBytes("state_payload"), result.getLong("revision"))
                else {
                    if (!owns(connection, playerUuid.bytes(), serverId)) throw PlayerStateOwnershipException(playerUuid, serverId)
                    null
                }
            }
        }
    }

    override fun save(state: PlayerState, serverId: String): PlayerState {
        val uuid = state.playerUuid.bytes()
        val payload = StateBlob.encode(state)
        dataSource.connection.use { connection ->
            if (state.revision == 0L) {
                val count = try {
                    connection.prepareStatement(
                        "INSERT INTO player_state (player_uuid, state_payload, revision) " +
                            "SELECT ?, ?, 1 FROM players WHERE uuid = ? AND online = TRUE AND current_server = ?",
                    ).use { statement ->
                        statement.setBytes(1, uuid); statement.setBytes(2, payload)
                        statement.setBytes(3, uuid); statement.setString(4, serverId)
                        statement.executeUpdate()
                    }
                } catch (failure: java.sql.SQLException) {
                    if (failure.errorCode == 1062) throw PlayerStateConflictException(state.playerUuid)
                    throw failure
                }
                if (count != 1) {
                    if (!owns(connection, uuid, serverId)) throw PlayerStateOwnershipException(state.playerUuid, serverId)
                    throw PlayerStateConflictException(state.playerUuid)
                }
            } else {
                val count = connection.prepareStatement(
                    "UPDATE player_state SET state_payload = ?, revision = revision + 1, updated_at = CURRENT_TIMESTAMP(3) " +
                        "WHERE player_uuid = ? AND revision = ? AND EXISTS (SELECT 1 FROM players " +
                        "WHERE players.uuid = player_state.player_uuid AND online = TRUE AND current_server = ?)",
                ).use { statement ->
                    statement.setBytes(1, payload); statement.setBytes(2, uuid)
                    statement.setLong(3, state.revision); statement.setString(4, serverId)
                    statement.executeUpdate()
                }
                if (count != 1) {
                    if (!owns(connection, uuid, serverId)) throw PlayerStateOwnershipException(state.playerUuid, serverId)
                    throw PlayerStateConflictException(state.playerUuid)
                }
            }
        }
        return state.withRevision(state.revision + 1)
    }

    private fun owns(connection: java.sql.Connection, uuid: ByteArray, serverId: String): Boolean =
        connection.prepareStatement("SELECT 1 FROM players WHERE uuid = ? AND online = TRUE AND current_server = ?").use { statement ->
            statement.setBytes(1, uuid); statement.setString(2, serverId)
            statement.executeQuery().use { it.next() }
        }

    private fun UUID.bytes(): ByteArray = ByteBuffer.allocate(16).putLong(mostSignificantBits).putLong(leastSignificantBits).array()

    private object StateBlob {
        private val MAGIC = byteArrayOf(0x54, 0x53, 0x50, 0x53, 0, 0, 0, 1)
        private const val MAX_BYTES = 64 * 1024 * 1024

        fun encode(state: PlayerState): ByteArray = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.write(MAGIC); out.writeInt(PlayerState.INVENTORY_SLOTS)
                state.inventory.forEach { out.writeItem(it) }
                out.writeInt(PlayerState.ARMOR_SLOTS); state.armor.forEach { out.writeItem(it) }
                out.writeItem(state.offhand)
                out.writeDouble(state.health); out.writeInt(state.foodLevel); out.writeFloat(state.experience)
                out.writeInt(state.level); out.writeInt(state.totalExperience)
            }
        }.toByteArray().also { require(it.size <= MAX_BYTES) { "Player state exceeds payload limit" } }

        fun decode(uuid: UUID, bytes: ByteArray, revision: Long): PlayerState {
            require(bytes.size <= MAX_BYTES) { "Player state exceeds payload limit" }
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val magic = ByteArray(MAGIC.size); input.readFully(magic)
                require(magic.contentEquals(MAGIC)) { "Unsupported player-state encoding" }
                require(input.readInt() == PlayerState.INVENTORY_SLOTS)
                val inventory = List(PlayerState.INVENTORY_SLOTS) { input.readItem() }
                require(input.readInt() == PlayerState.ARMOR_SLOTS)
                val armor = List(PlayerState.ARMOR_SLOTS) { input.readItem() }
                val offhand = input.readItem()
                val state = PlayerState(uuid, inventory, armor, offhand, input.readDouble(), input.readInt(), input.readFloat(), input.readInt(), input.readInt(), revision)
                require(input.available() == 0) { "Trailing bytes in player-state payload" }
                return state
            }
        }

        private fun DataOutputStream.writeItem(item: SerializedPlayerItem?) {
            val bytes = item?.payload
            require(bytes == null || bytes.size <= MAX_BYTES) { "Item payload exceeds limit" }
            writeInt(bytes?.size ?: -1); if (bytes != null) write(bytes)
        }

        private fun DataInputStream.readItem(): SerializedPlayerItem? {
            val size = readInt()
            require(size == -1 || size in 1..MAX_BYTES) { "Invalid item payload size" }
            return if (size == -1) null else SerializedPlayerItem(ByteArray(size).also(::readFully))
        }
    }
}
