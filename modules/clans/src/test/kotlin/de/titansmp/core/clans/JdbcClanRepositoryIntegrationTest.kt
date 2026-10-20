package de.titansmp.core.clans

import de.titansmp.api.clans.*
import de.titansmp.core.database.DatabaseMigrator
import de.titansmp.core.database.DatabaseService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mariadb.MariaDBContainer
import java.nio.ByteBuffer
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcClanRepositoryIntegrationTest {
    @Container private val maria=MariaDBContainer("mariadb:11.4")
    private lateinit var dataSource:org.mariadb.jdbc.MariaDbDataSource
    private lateinit var repo:JdbcClanRepository
    private val now=Instant.parse("2026-10-20T12:00:00Z")

    @BeforeEach fun setUp(){
        if(!::dataSource.isInitialized){
            dataSource=org.mariadb.jdbc.MariaDbDataSource().apply { setUrl(maria.jdbcUrl);setUser(maria.username);setPassword(maria.password) }
            DatabaseMigrator().migrate(object:DatabaseService { override val dataSource=this@JdbcClanRepositoryIntegrationTest.dataSource;override fun start()=Unit;override fun close()=Unit })
        }
        dataSource.connection.use { c->c.createStatement().use { s -> s.executeUpdate("DELETE FROM clan_invitations");s.executeUpdate("DELETE FROM clan_members");s.executeUpdate("DELETE FROM clans");s.executeUpdate("DELETE FROM clan_player_locks");s.executeUpdate("DELETE FROM players") } }
        repo=JdbcClanRepository(dataSource)
    }

    @Test fun `creator becomes Admin and role transitions preserve final Admin`() {
        val owner=player("Owner");val member=player("Member")
        val clan=repo.create(owner,"North Wolves","NW",now)
        assertEquals(ClanRole.ADMIN,repo.members(clan.uuid,1,10).entries.single().role)
        repo.invite(owner,member,now,now.plusSeconds(3600))
        assertEquals(ClanRole.MEMBER,repo.accept(member,clan.uuid,now).let { repo.members(it.uuid,1,10).entries.first { m->m.playerUuid==member }.role })
        assertEquals(ClanRole.MODERATOR,repo.promote(owner,member))
        assertEquals(ClanRole.ADMIN,repo.promote(owner,member))
        assertFailsWith<ClanOperationException> { repo.demote(member,member) }
        assertEquals(ClanRole.MODERATOR,repo.demote(owner,member))
        assertEquals(ClanRole.MEMBER,repo.demote(owner,member))
        assertFailsWith<ClanOperationException> { repo.leave(owner) }
    }

    @Test fun `unique name and tag are case insensitive and a player can create one clan`() {
        val owner=player("Owner");val second=player("Second")
        repo.create(owner,"Wolves","WOLF",now)
        assertTrue(repo.nameExists("wOlVeS"));assertTrue(repo.tagExists("wolf"))
        assertFailsWith<java.sql.SQLException> { repo.create(second,"wolves","NEXT",now) }
        assertEquals(ClanFailure.ALREADY_IN_CLAN,assertFailsWith<ClanOperationException> { repo.create(owner,"Another","NEXT",now) }.reason)
    }

    @Test fun `invitation expiry reject and kick lifecycle`() {
        val owner=player("Owner");val invited=player("Invited");val clan=repo.create(owner,"Clan One","ONE",now)
        repo.invite(owner,invited,now,now.plusSeconds(60))
        assertFailsWith<ClanOperationException> { repo.accept(invited,clan.uuid,now.plusSeconds(61)) }
        repo.invitations(invited,1,10,now.plusSeconds(61))
        assertEquals(0,repo.invitations(invited,1,10,now.plusSeconds(61)).entries.size)
        repo.invite(owner,invited,now,now.plusSeconds(600));repo.accept(invited,clan.uuid,now.plusSeconds(1))
        repo.kick(owner,invited)
        assertEquals(null,repo.byMember(invited))
    }

    @Test fun `simultaneous accepts of two invitations leave player in one clan`() {
        val first=player("First");val second=player("Second");val target=player("Target")
        val a=repo.create(first,"Clan Alpha","ALPHA",now);val b=repo.create(second,"Clan Beta","BETA",now)
        repo.invite(first,target,now,now.plusSeconds(3600));repo.invite(second,target,now,now.plusSeconds(3600))
        val pool=Executors.newFixedThreadPool(2)
        try {
            val futures=listOf(pool.submit<Boolean> { runCatching { repo.accept(target,a.uuid,now) }.isSuccess },pool.submit<Boolean> { runCatching { repo.accept(target,b.uuid,now) }.isSuccess })
            assertEquals(1,futures.count { it.get(20,TimeUnit.SECONDS) })
            assertEquals(1,dataSource.connection.use { c->c.prepareStatement("SELECT COUNT(*) FROM clan_members WHERE player_uuid=?").use { it.setBytes(1,target.bytes());it.executeQuery().use { r->r.next();r.getInt(1) } } })
            assertNotEquals(null,repo.byMember(target))
        } finally { pool.shutdownNow() }
    }

    @Test fun `disband cascades membership and invitations`() {
        val owner=player("Owner");val target=player("Target");val clan=repo.create(owner,"Clan One","ONE",now)
        repo.invite(owner,target,now,now.plusSeconds(3600))
        val (deleted,members)=repo.disband(owner)
        assertEquals(clan.uuid,deleted.uuid);assertEquals(listOf(owner),members)
        assertEquals(null,repo.get(clan.uuid));assertEquals(0,repo.invitations(target,1,10,now).total)
    }

    private fun player(name:String):UUID=UUID.randomUUID().also { id->dataSource.connection.use { c->c.prepareStatement("INSERT INTO players(uuid,username,first_join,last_seen,current_server,online) VALUES(?,?,?,?,NULL,FALSE)").use { it.setBytes(1,id.bytes());it.setString(2,name);it.setTimestamp(3,java.sql.Timestamp.from(now));it.setTimestamp(4,java.sql.Timestamp.from(now));it.executeUpdate() } } }
    private fun UUID.bytes():ByteArray=ByteBuffer.allocate(16).putLong(mostSignificantBits).putLong(leastSignificantBits).array()
}
