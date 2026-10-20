package de.titansmp.core.friends

import de.titansmp.core.database.DatabaseMigrator
import de.titansmp.core.database.DatabaseService
import de.titansmp.api.friends.FriendRequestResult
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mariadb.MariaDBContainer
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcFriendsRepositoryIntegrationTest {
    @Container private val maria = MariaDBContainer("mariadb:11.4")
    private lateinit var repository: JdbcFriendsRepository

    @BeforeEach fun prepare() {
        val ds = org.mariadb.jdbc.MariaDbDataSource().apply { setUrl(maria.jdbcUrl); setUser(maria.username); setPassword(maria.password) }
        DatabaseMigrator().migrate(object : DatabaseService {
            override val dataSource = ds
            override fun start() = Unit
            override fun close() = Unit
        })
        ds.connection.use { c -> c.createStatement().use { s ->
            s.executeUpdate("DELETE FROM friend_requests"); s.executeUpdate("DELETE FROM friendships"); s.executeUpdate("DELETE FROM friend_blocks"); s.executeUpdate("DELETE FROM friend_pair_locks")
        } }
        repository = JdbcFriendsRepository(ds)
    }

    @Test fun `request create reverse duplicate accept and removal lifecycle`() {
        val a = UUID.randomUUID(); val b = UUID.randomUUID()
        assertEquals(FriendRequestResult.CREATED, repository.send(a, b))
        assertEquals(FriendRequestResult.INCOMING_REQUEST_EXISTS, repository.send(b, a))
        assertTrue(repository.accept(b, a))
        assertTrue(repository.areFriends(a, b))
        assertEquals(0L, repository.requests(b, 1, 10).total)
        assertTrue(repository.remove(a, b))
        assertFalse(repository.areFriends(a, b))
    }

    @Test fun `self duplicate and blocked requests are rejected`() {
        val a = UUID.randomUUID(); val b = UUID.randomUUID()
        assertEquals(FriendRequestResult.SELF, repository.send(a, a))
        assertEquals(FriendRequestResult.CREATED, repository.send(a, b))
        assertEquals(FriendRequestResult.DUPLICATE, repository.send(a, b))
        assertTrue(repository.block(b, a))
        assertEquals(FriendRequestResult.BLOCKED, repository.send(a, b))
        assertFalse(repository.accept(b, a))
        assertFalse(repository.unblock(a, b))
        assertTrue(repository.unblock(b, a))
    }

    @Test fun `blocking removes friendship and both pending directions`() {
        val a = UUID.randomUUID(); val b = UUID.randomUUID()
        repository.send(a, b); assertTrue(repository.accept(b, a))
        assertTrue(repository.block(a, b))
        assertFalse(repository.areFriends(a, b))
        assertEquals(FriendRequestResult.BLOCKED, repository.send(b, a))
        assertTrue(repository.unblock(a, b))
        assertFalse(repository.areFriends(a, b))
    }

    @Test fun `concurrent duplicate request has one winner`() {
        val a = UUID.randomUUID(); val b = UUID.randomUUID()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = (1..16).map { pool.submit<FriendRequestResult> { repository.send(a, b) } }.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it == FriendRequestResult.CREATED })
            assertEquals(15, results.count { it == FriendRequestResult.DUPLICATE })
        } finally { pool.shutdownNow() }
    }

    @Test fun `friend pages are limited`() {
        val a = UUID.randomUUID()
        repeat(3) { index -> val b = UUID.randomUUID(); repository.send(a, b); assertTrue(repository.accept(b, a)) }
        val page = repository.friends(a, 2, 2)
        assertEquals(2, page.page)
        assertEquals(2, page.pageSize)
        assertEquals(3, page.total)
        assertEquals(1, page.entries.size)
    }
}
