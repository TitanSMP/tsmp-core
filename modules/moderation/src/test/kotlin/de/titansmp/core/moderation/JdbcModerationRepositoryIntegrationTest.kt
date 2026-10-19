package de.titansmp.core.moderation

import de.titansmp.api.moderation.ActivePunishment
import de.titansmp.api.moderation.ModerationActionType
import de.titansmp.api.moderation.ModerationActor
import de.titansmp.api.moderation.ModerationTarget
import de.titansmp.api.moderation.PunishmentType
import de.titansmp.core.database.DatabaseMigrator
import de.titansmp.core.database.DatabaseService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mariadb.MariaDBContainer
import java.time.Instant
import java.util.UUID

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcModerationRepositoryIntegrationTest {
    @Container
    private val mariaDb = MariaDBContainer("mariadb:11.4")

    private lateinit var dataSource: org.mariadb.jdbc.MariaDbDataSource
    private lateinit var repository: JdbcModerationRepository

    @BeforeEach
    fun setUp() {
        if (!::dataSource.isInitialized) {
            dataSource = org.mariadb.jdbc.MariaDbDataSource().apply {
                setUrl(mariaDb.jdbcUrl)
                setUser(mariaDb.username)
                setPassword(mariaDb.password)
            }
            DatabaseMigrator().migrate(object : DatabaseService {
                override val dataSource = this@JdbcModerationRepositoryIntegrationTest.dataSource
                override fun start() = Unit
                override fun close() = Unit
            })
        }
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("DELETE FROM moderation_actions")
                statement.executeUpdate("DELETE FROM moderation_punishments")
                statement.executeUpdate("DELETE FROM players")
            }
        }
        repository = JdbcModerationRepository(dataSource)
    }

    @Test
    fun `ban and unban preserve both audit entries and remove active punishment`() {
        val (actor, target) = createIdentities()
        val now = Instant.parse("2026-10-19T20:00:00Z")
        val ban = ActivePunishment(UUID.randomUUID(), target, PunishmentType.BAN, "Cheating", now, null, actor, "smp-01")
        repository.savePunishment(ban, ModerationActionType.BAN)

        assertEquals(ban, repository.findActive(target.uuid, PunishmentType.BAN, now))
        assertTrue(repository.removePunishment(target, PunishmentType.BAN, actor, ModerationActionType.UNBAN, "Appeal", UUID.randomUUID(), now.plusSeconds(1), "smp-02"))
        assertNull(repository.findActive(target.uuid, PunishmentType.BAN, now.plusSeconds(2)))
        assertEquals(listOf("BAN", "UNBAN"), actionsFor(target.uuid))
    }

    @Test
    fun `expired mute is inactive and permanent mute has null expiry`() {
        val (actor, target) = createIdentities()
        val now = Instant.parse("2026-10-19T20:00:00Z")
        val timed = ActivePunishment(UUID.randomUUID(), target, PunishmentType.MUTE, "Spam", now, now.plusSeconds(60), actor, "smp-01")
        repository.savePunishment(timed, ModerationActionType.MUTE)
        assertNull(repository.findActive(target.uuid, PunishmentType.MUTE, now.plusSeconds(60)))

        val permanentTarget = ModerationTarget(UUID.randomUUID(), "Permanent")
        insertPlayer(permanentTarget)
        val permanent = ActivePunishment(UUID.randomUUID(), permanentTarget, PunishmentType.MUTE, "Spam", now, null, actor, "smp-01")
        repository.savePunishment(permanent, ModerationActionType.MUTE)
        assertNull(repository.findActive(permanentTarget.uuid, PunishmentType.MUTE, now.plusSeconds(100_000))?.expiresAt)
    }

    @Test
    fun `kick writes history without creating an active punishment`() {
        val (actor, target) = createIdentities()
        repository.recordKick(target, actor, "Rule violation", UUID.randomUUID(), Instant.now(), "smp-01")

        assertNull(repository.findActive(target.uuid, PunishmentType.BAN, Instant.now()))
        assertNull(repository.findActive(target.uuid, PunishmentType.MUTE, Instant.now()))
        assertEquals(listOf("KICK"), actionsFor(target.uuid))
    }

    private fun createIdentities(): Pair<ModerationActor, ModerationTarget> {
        val actor = ModerationActor(UUID.randomUUID(), "ModTest")
        val target = ModerationTarget(UUID.randomUUID(), "TargetTest")
        insertPlayer(target)
        return actor to target
    }

    private fun insertPlayer(player: ModerationTarget) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO players (uuid, username, first_join, last_seen, current_server, online) " +
                    "VALUES (?, ?, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), NULL, FALSE)",
            ).use { statement ->
                statement.setBytes(1, player.uuid.asBytes())
                statement.setString(2, player.name)
                statement.executeUpdate()
            }
        }
    }

    private fun actionsFor(target: UUID): List<String> = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT action FROM moderation_actions WHERE target_uuid=? ORDER BY created_at, action").use { statement ->
            statement.setBytes(1, target.asBytes())
            statement.executeQuery().use { result -> buildList { while (result.next()) add(result.getString(1)) } }
        }
    }

    private fun UUID.asBytes(): ByteArray = java.nio.ByteBuffer.allocate(16).putLong(mostSignificantBits).putLong(leastSignificantBits).array()
}
