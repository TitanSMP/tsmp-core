package de.titansmp.core.moderation

import de.titansmp.api.moderation.ActivePunishment
import de.titansmp.api.moderation.ModerationActionType
import de.titansmp.api.moderation.ModerationActor
import de.titansmp.api.moderation.ModerationTarget
import de.titansmp.api.moderation.PunishmentType
import java.nio.ByteBuffer
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/** Transactional JDBC adapter using the shared TitanSMP DataSource. */
class JdbcModerationRepository(private val dataSource: DataSource) : ModerationRepository {
    override fun savePunishment(punishment: ActivePunishment, action: ModerationActionType) = transaction { connection ->
        connection.prepareStatement(
            "INSERT INTO moderation_punishments (id, target_uuid, target_name, type, reason, created_at, expires_at, actor_uuid, actor_name, server_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE id=VALUES(id), target_name=VALUES(target_name), reason=VALUES(reason), " +
                "created_at=VALUES(created_at), expires_at=VALUES(expires_at), actor_uuid=VALUES(actor_uuid), actor_name=VALUES(actor_name), server_id=VALUES(server_id)",
        ).use { statement ->
            statement.setBytes(1, punishment.id.toBytes()); statement.setBytes(2, punishment.target.uuid.toBytes())
            statement.setString(3, punishment.target.name); statement.setString(4, punishment.type.name); statement.setString(5, punishment.reason)
            statement.setUtc(6, punishment.createdAt); statement.setUtcOrNull(7, punishment.expiresAt); statement.setUuidOrNull(8, punishment.actor.uuid)
            statement.setString(9, punishment.actor.name); statement.setString(10, punishment.serverId); statement.executeUpdate()
        }
        insertAction(connection, punishment.id, punishment.target, punishment.actor, action, punishment.reason, punishment.createdAt, punishment.expiresAt, punishment.serverId)
    }

    override fun removePunishment(target: ModerationTarget, type: PunishmentType, actor: ModerationActor, action: ModerationActionType, reason: String?, actionId: UUID, at: Instant, serverId: String): Boolean = transaction { connection ->
        val deleted = connection.prepareStatement(
            "DELETE FROM moderation_punishments WHERE target_uuid=? AND type=? AND (expires_at IS NULL OR expires_at > ?)",
        ).use { statement ->
            statement.setBytes(1, target.uuid.toBytes()); statement.setString(2, type.name); statement.setUtc(3, at); statement.executeUpdate()
        }
        if (deleted == 1) insertAction(connection, actionId, target, actor, action, reason, at, null, serverId)
        deleted == 1
    }

    override fun recordKick(target: ModerationTarget, actor: ModerationActor, reason: String, actionId: UUID, at: Instant, serverId: String) = transaction { connection ->
        insertAction(connection, actionId, target, actor, ModerationActionType.KICK, reason, at, null, serverId)
    }

    override fun findActive(targetId: UUID, type: PunishmentType, now: Instant): ActivePunishment? = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT * FROM moderation_punishments WHERE target_uuid=? AND type=? AND (expires_at IS NULL OR expires_at > ?)").use { statement ->
            statement.setBytes(1, targetId.toBytes()); statement.setString(2, type.name); statement.setUtc(3, now)
            statement.executeQuery().use { result -> if (result.next()) result.toPunishment() else null }
        }
    }

    private fun insertAction(connection: Connection, id: UUID, target: ModerationTarget, actor: ModerationActor, action: ModerationActionType, reason: String?, at: Instant, expiresAt: Instant?, serverId: String) {
        connection.prepareStatement(
            "INSERT INTO moderation_actions (id, target_uuid, target_name, actor_uuid, actor_name, action, reason, created_at, expires_at, server_id, metadata) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)",
        ).use { statement ->
            statement.setBytes(1, id.toBytes()); statement.setBytes(2, target.uuid.toBytes()); statement.setString(3, target.name)
            statement.setUuidOrNull(4, actor.uuid); statement.setString(5, actor.name); statement.setString(6, action.name)
            statement.setString(7, reason); statement.setUtc(8, at); statement.setUtcOrNull(9, expiresAt); statement.setString(10, serverId)
            statement.executeUpdate()
        }
    }

    private fun ResultSet.toPunishment(): ActivePunishment {
        val actorId = getBytes("actor_uuid")?.toUuid()
        val expires = getObject("expires_at", LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC)
        return ActivePunishment(
            getBytes("id").toUuid(),
            ModerationTarget(getBytes("target_uuid").toUuid(), getString("target_name")),
            PunishmentType.valueOf(getString("type")), getString("reason"), getObject("created_at", LocalDateTime::class.java).toInstant(ZoneOffset.UTC),
            expires, ModerationActor(actorId, getString("actor_name")), getString("server_id"),
        )
    }

    private fun <T> transaction(block: (Connection) -> T): T {
        dataSource.connection.use { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try { return block(connection).also { connection.commit() } } catch (failure: Throwable) {
                try { connection.rollback() } catch (rollback: Throwable) { failure.addSuppressed(rollback) }
                throw failure
            } finally { connection.autoCommit = previousAutoCommit }
        }
    }

    private fun PreparedStatement.setUtc(index: Int, instant: Instant) = setObject(index, LocalDateTime.ofInstant(instant, ZoneOffset.UTC))
    private fun PreparedStatement.setUtcOrNull(index: Int, instant: Instant?) = if (instant == null) setNull(index, java.sql.Types.TIMESTAMP) else setUtc(index, instant)
    private fun PreparedStatement.setUuidOrNull(index: Int, uuid: UUID?) = if (uuid == null) setNull(index, java.sql.Types.BINARY) else setBytes(index, uuid.toBytes())
    private fun UUID.toBytes(): ByteArray = ByteBuffer.allocate(16).putLong(mostSignificantBits).putLong(leastSignificantBits).array()
    private fun ByteArray.toUuid(): UUID = ByteBuffer.wrap(this).let { UUID(it.long, it.long) }
}
