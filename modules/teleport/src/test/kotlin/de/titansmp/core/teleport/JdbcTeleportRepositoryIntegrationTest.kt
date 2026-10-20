package de.titansmp.core.teleport

import de.titansmp.api.teleport.*
import de.titansmp.core.database.DatabaseMigrator
import de.titansmp.core.database.DatabaseService
import org.junit.jupiter.api.BeforeAll
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
import kotlin.test.assertNull

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcTeleportRepositoryIntegrationTest {
    @Container private val maria=MariaDBContainer("mariadb:11.4")
    private lateinit var ds:org.mariadb.jdbc.MariaDbDataSource
    private lateinit var repo:JdbcTeleportRepository
    private val now=Instant.parse("2026-10-20T12:00:00Z")

    @BeforeAll fun migrate() {
        ds=org.mariadb.jdbc.MariaDbDataSource().apply { setUrl(maria.jdbcUrl);setUser(maria.username);setPassword(maria.password) }
        DatabaseMigrator().migrate(object:DatabaseService { override val dataSource=ds;override fun start()=Unit;override fun close()=Unit })
        repo=JdbcTeleportRepository(ds)
    }

    @Test fun `home create duplicate limit and delete are enforced transactionally`() {
        val owner=player("HomeOwner")
        val home=repo.setHome(owner,"base","Base",1,location("backend-a"))
        assertEquals("Base",home.displayName)
        assertEquals("backend-a",repo.getHome(owner,"base")!!.destination.serverId)
        assertFailsWith<TeleportOperationException> { repo.setHome(owner,"base","Base Again",0,location("backend-b")) }
        assertFailsWith<TeleportOperationException> { repo.setHome(owner,"farm","Farm",1,location("backend-b")) }
        assertEquals(1,repo.listHomes(owner).size)
        assertEquals(true,repo.deleteHome(owner,"base"))
        assertNull(repo.getHome(owner,"base"))
    }

    @Test fun `simultaneous home writes respect limit`() {
        val owner=player("RaceOwner");val pool=Executors.newFixedThreadPool(2)
        try {
            val writes=listOf("base","farm").map { name -> pool.submit<Boolean> { runCatching { repo.setHome(owner,name,name,1,location("backend"));true }.getOrDefault(false) } }
            assertEquals(1,writes.count { it.get(10,TimeUnit.SECONDS) })
            assertEquals(1,repo.listHomes(owner).size)
        } finally { pool.shutdownNow() }
    }

    @Test fun `clan homes require admin and serialize capacity`() {
        val owner=player("ClanOwner");val member=player("ClanMember");val clan=UUID.randomUUID()
        ds.connection.use { c ->
            c.prepareStatement("INSERT INTO clans(clan_uuid,name,normalized_name,tag,normalized_tag,created_at) VALUES(?,?,?,?,?,?)").use { s -> s.setBytes(1,clan.bytes());s.setString(2,"Homes");s.setString(3,"homes-${clan.toString().take(6)}");s.setString(4,"HM${clan.toString().take(2)}");s.setString(5,"hm${clan.toString().take(2)}");s.setTimestamp(6,java.sql.Timestamp.from(now));s.executeUpdate() }
            c.prepareStatement("INSERT INTO clan_members(player_uuid,clan_uuid,role,joined_at) VALUES(?,?,?,?)").use { s -> s.setBytes(1,owner.bytes());s.setBytes(2,clan.bytes());s.setString(3,"ADMIN");s.setTimestamp(4,java.sql.Timestamp.from(now));s.executeUpdate() }
            c.prepareStatement("INSERT INTO clan_members(player_uuid,clan_uuid,role,joined_at) VALUES(?,?,?,?)").use { s -> s.setBytes(1,member.bytes());s.setBytes(2,clan.bytes());s.setString(3,"MODERATOR");s.setTimestamp(4,java.sql.Timestamp.from(now));s.executeUpdate() }
        }
        repo.setClanHome(owner,clan,"base","Base",3,location("backend"))
        assertFailsWith<TeleportOperationException> { repo.setClanHome(member,clan,"farm","Farm",3,location("backend")) }
        assertEquals("base",repo.getClanHome(clan,null)!!.normalizedName)
        assertEquals(true,repo.deleteClanHome(owner,clan,"base"))
    }

    @Test fun `requests accept oldest and expired request is removed`() {
        val requester=player("Requester");val target=player("Target")
        val old=TeleportRequest(UUID.randomUUID(),requester,target,"Requester",TeleportDirection.REQUESTER_TO_TARGET,now,now.plusSeconds(60))
        val newer=old.copy(requestId=UUID.randomUUID(),createdAt=now.plusSeconds(1),expiresAt=now.plusSeconds(61))
        repo.createRequest(old);repo.createRequest(newer)
        assertEquals(old.requestId,repo.resolveRequest(target,now.plusSeconds(2)).requestId)
        assertEquals(newer.requestId,repo.resolveRequest(target,now.plusSeconds(2)).requestId)
        val expired=old.copy(requestId=UUID.randomUUID(),createdAt=now.plusSeconds(2),expiresAt=now.plusSeconds(3))
        repo.createRequest(expired)
        assertFailsWith<TeleportOperationException> { repo.resolveRequest(target,now.plusSeconds(4)) }
        assertFailsWith<TeleportOperationException> { repo.resolveRequest(target,now.plusSeconds(4)) }
    }

    private fun player(name:String):UUID=UUID.randomUUID().also { id -> ds.connection.use { c -> c.prepareStatement("INSERT INTO players(uuid,username,first_join,last_seen,current_server,online) VALUES(?,?,?,?,?,TRUE)").use { s -> s.setBytes(1,id.bytes());s.setString(2,name);s.setTimestamp(3,java.sql.Timestamp.from(now));s.setTimestamp(4,java.sql.Timestamp.from(now));s.setString(5,"backend-a");s.executeUpdate() } } }
    private fun location(server:String)=StoredTeleportLocation(server,TeleportLocation("world",10.5,64.0,-4.5,90f,0f))
    private fun UUID.bytes()=ByteBuffer.allocate(16).putLong(mostSignificantBits).putLong(leastSignificantBits).array()
}
