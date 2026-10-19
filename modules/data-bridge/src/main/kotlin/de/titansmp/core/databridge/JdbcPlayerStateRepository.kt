package de.titansmp.core.databridge

import de.titansmp.api.databridge.PlayerState
import de.titansmp.api.databridge.PlayerStateConflictException
import de.titansmp.api.databridge.PlayerStateOwnershipException
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
                if (result.next()) PlayerStatePayloadCodec.decode(playerUuid, result.getBytes("state_payload"), result.getLong("revision"))
                else {
                    if (!owns(connection, playerUuid.bytes(), serverId)) throw PlayerStateOwnershipException(playerUuid, serverId)
                    null
                }
            }
        }
    }

    override fun save(state: PlayerState, serverId: String): PlayerState {
        val uuid = state.playerUuid.bytes()
        val payload = PlayerStatePayloadCodec.encode(state)
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

}
