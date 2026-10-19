package de.titansmp.core.enderchest

import de.titansmp.api.enderchest.EnderChestStorage
import de.titansmp.api.enderchest.EnderChestStorageConflictException
import de.titansmp.api.enderchest.EnderChestSessionOwnershipException
import de.titansmp.api.enderchest.SerializedEnderChestItem
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import javax.sql.DataSource

/** MariaDB repository using TitanSMP's shared DataSource and revision-checked writes. */
class JdbcEnderChestRepository(private val dataSource: DataSource) : EnderChestRepository {
    override fun load(playerId: UUID, serverId: String): EnderChestStorage = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT storage.contents, storage.revision FROM players " +
                "LEFT JOIN ender_chest_storage storage ON storage.player_uuid = players.uuid " +
                "WHERE players.uuid = ? AND players.online = TRUE AND players.current_server = ?",
        ).use { statement ->
            statement.setBytes(1, playerId.toBytes())
            statement.setString(2, serverId)
            statement.executeQuery().use { result ->
                if (!result.next()) throw EnderChestSessionOwnershipException(playerId, serverId)
                val contents = result.getBytes("contents") ?: return EnderChestStorage.empty(playerId)
                EnderChestStorage(playerId, StorageBlobCodec.decode(contents), result.getLong("revision"))
            }
        }
    }

    override fun save(storage: EnderChestStorage, serverId: String): EnderChestStorage {
        val encoded = StorageBlobCodec.encode(storage.slots)
        val uuid = storage.playerId.toBytes()
        dataSource.connection.use { connection ->
            if (storage.revision == 0L) {
                try {
                    connection.prepareStatement(
                        "INSERT INTO ender_chest_storage (player_uuid, contents, revision) " +
                            "SELECT ?, ?, 1 FROM players WHERE uuid = ? AND (online = FALSE OR current_server = ?)",
                    ).use { statement ->
                        statement.setBytes(1, uuid)
                        statement.setBytes(2, encoded)
                        statement.setBytes(3, uuid)
                        statement.setString(4, serverId)
                        if (statement.executeUpdate() != 1) throw EnderChestSessionOwnershipException(storage.playerId, serverId)
                    }
                } catch (failure: java.sql.SQLIntegrityConstraintViolationException) {
                    if (failure.errorCode == 1062) throw EnderChestStorageConflictException(storage.playerId)
                    throw failure
                } catch (failure: java.sql.SQLException) {
                    if (failure.errorCode == 1062) {
                        throw EnderChestStorageConflictException(storage.playerId)
                    }
                    throw failure
                }
            } else {
                connection.prepareStatement(
                    "UPDATE ender_chest_storage storage SET contents = ?, revision = revision + 1, updated_at = CURRENT_TIMESTAMP(3) " +
                        "WHERE player_uuid = ? AND revision = ? AND EXISTS (" +
                        "SELECT 1 FROM players WHERE players.uuid = storage.player_uuid " +
                        "AND (players.online = FALSE OR players.current_server = ?))",
                ).use { statement ->
                    statement.setBytes(1, encoded)
                    statement.setBytes(2, uuid)
                    statement.setLong(3, storage.revision)
                    statement.setString(4, serverId)
                    if (statement.executeUpdate() != 1) throw EnderChestStorageConflictException(storage.playerId)
                }
            }
        }
        return storage.copy(revision = storage.revision + 1)
    }

    private fun UUID.toBytes(): ByteArray = ByteBuffer.allocate(16).putLong(mostSignificantBits).putLong(leastSignificantBits).array()

    private object StorageBlobCodec {
        private val MAGIC = byteArrayOf(0x54, 0x53, 0x45, 0x43, 0x00, 0x00, 0x00, 0x01)
        private const val MAX_PAYLOAD_BYTES = 16 * 1024 * 1024

        fun encode(slots: List<SerializedEnderChestItem?>): ByteArray {
            require(slots.size == EnderChestStorage.CAPACITY)
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                out.write(MAGIC)
                out.writeInt(slots.size)
                slots.forEach { item ->
                    val payload = item?.payload
                    require(payload == null || payload.size <= MAX_PAYLOAD_BYTES) { "Serialized item exceeds storage limit" }
                    out.writeInt(payload?.size ?: -1)
                    if (payload != null) out.write(payload)
                }
            }
            return bytes.toByteArray().also {
                require(it.size <= MAX_PAYLOAD_BYTES) { "EnderChest storage exceeds storage limit" }
            }
        }

        fun decode(bytes: ByteArray): List<SerializedEnderChestItem?> {
            require(bytes.size <= MAX_PAYLOAD_BYTES) { "EnderChest storage exceeds storage limit" }
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val magic = ByteArray(MAGIC.size)
                input.readFully(magic)
                require(magic.contentEquals(MAGIC)) { "Unsupported EnderChest storage format" }
                val slots = input.readInt()
                require(slots == EnderChestStorage.CAPACITY) { "Unexpected EnderChest slot count: $slots" }
                val result = ArrayList<SerializedEnderChestItem?>(slots)
                repeat(slots) {
                    val size = input.readInt()
                    require(size == -1 || size in 1..MAX_PAYLOAD_BYTES) { "Invalid item payload size: $size" }
                    result += if (size == -1) null else ByteArray(size).also(input::readFully).let(::SerializedEnderChestItem)
                }
                require(input.available() == 0) { "Trailing bytes in EnderChest storage" }
                return result
            }
        }
    }
}
