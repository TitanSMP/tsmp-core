package de.titansmp.shop

import de.titansmp.api.shop.ShopCategoryState
import java.sql.Connection
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

class ShopCategoryStateRepository(private val dataSource: DataSource) {
    fun load(categoryIds: Set<String>): Map<String, ShopCategoryState> {
        if (categoryIds.isEmpty()) return emptyMap()
        return dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT category_id, enabled, updated_by, updated_at FROM shop_category_state WHERE category_id IN (${categoryIds.joinToString(",") { "?" }})").use { statement ->
                categoryIds.sorted().forEachIndexed { index, id -> statement.setString(index + 1, id) }
                statement.executeQuery().use { rows -> buildMap { while (rows.next()) put(rows.getString("category_id"), rows.state()) } }
            }
        }.let { stored -> categoryIds.associateWith { stored[it] ?: ShopCategoryState(it, true, null, Instant.EPOCH) } }
    }

    fun set(categoryId: String, enabled: Boolean, actor: UUID, serverId: String, now: Instant): ShopCategoryState = transaction { c ->
        c.prepareStatement("INSERT INTO shop_category_state(category_id,enabled,updated_by,updated_at) VALUES(?,TRUE,NULL,?) ON DUPLICATE KEY UPDATE category_id=VALUES(category_id)").use {
            it.setString(1, categoryId); it.setTimestamp(2, Timestamp(now)); it.executeUpdate()
        }
        val old = c.prepareStatement("SELECT enabled FROM shop_category_state WHERE category_id=? FOR UPDATE").use { s -> s.setString(1, categoryId); s.executeQuery().use { r -> r.next(); r.getBoolean(1) } }
        c.prepareStatement("UPDATE shop_category_state SET enabled=?,updated_by=?,updated_at=? WHERE category_id=?").use {
            it.setBoolean(1, enabled); it.setBytes(2, actor.bytes()); it.setTimestamp(3, Timestamp(now)); it.setString(4, categoryId); it.executeUpdate()
        }
        c.prepareStatement("INSERT INTO shop_category_state_audit(category_id,old_enabled,new_enabled,updated_by,updated_at,server_id) VALUES(?,?,?,?,?,?)").use {
            it.setString(1, categoryId); it.setBoolean(2, old); it.setBoolean(3, enabled); it.setBytes(4, actor.bytes()); it.setTimestamp(5, Timestamp(now)); it.setString(6, serverId); it.executeUpdate()
        }
        ShopCategoryState(categoryId, enabled, actor, now)
    }

    private fun <T> transaction(block: (Connection) -> T): T = dataSource.connection.use { c ->
        val auto = c.autoCommit; c.autoCommit = false
        try { block(c).also { c.commit() } } catch (t: Throwable) { c.rollback(); throw t } finally { c.autoCommit = auto }
    }
    private fun java.sql.ResultSet.state() = ShopCategoryState(getString("category_id"), getBoolean("enabled"), getBytes("updated_by")?.uuid(), getTimestamp("updated_at").toInstant())
    private fun Timestamp(instant: Instant) = java.sql.Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC))
    private fun UUID.bytes() = java.nio.ByteBuffer.allocate(16).putLong(mostSignificantBits).putLong(leastSignificantBits).array()
    private fun ByteArray.uuid(): UUID = java.nio.ByteBuffer.wrap(this).let { UUID(it.long, it.long) }
}
