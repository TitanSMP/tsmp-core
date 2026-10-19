package de.titansmp.core.enderchest

import de.titansmp.api.enderchest.EnderChestPermissionCheck
import de.titansmp.api.enderchest.EnderChestStorage
import de.titansmp.api.enderchest.EnderChestStorageConflictException
import de.titansmp.api.enderchest.SerializedEnderChestItem
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import java.util.concurrent.CompletionException

class EnderChestServiceTest {
    private val repository = InMemoryEnderChestRepository()
    private val service = DefaultEnderChestService(repository, java.util.concurrent.Executor { it.run() })

    @Test
    fun `new player receives a complete empty storage`() {
        val storage = service.load(UUID.randomUUID(), "smp-01").toCompletableFuture().join()
        assertEquals(54, storage.slots.size)
        assertEquals(0, storage.revision)
        assertEquals(54, storage.slots.count { it == null })
    }

    @Test
    fun `save and load preserve serialized items and empty slots`() {
        val uuid = UUID.randomUUID()
        val slots = MutableList<SerializedEnderChestItem?>(54) { null }
        slots[0] = SerializedEnderChestItem(byteArrayOf(1, 2, 3))
        slots[53] = SerializedEnderChestItem(byteArrayOf(4, 5, 6))
        service.save(EnderChestStorage.empty(uuid).copy(slots = slots), "smp-01").toCompletableFuture().join()

        val loaded = service.load(uuid, "smp-01").toCompletableFuture().join()
        assertEquals(54, loaded.slots.size)
        assertEquals(1, loaded.revision)
        assertEquals(slots, loaded.slots)
        assertNull(loaded.slots[1])
    }

    @Test
    fun `highest row permission wins and one row is the fallback`() {
        assertEquals(1, service.unlockedRows(EnderChestPermissionCheck { false }))
        assertEquals(1, service.unlockedRows(EnderChestPermissionCheck { it == "titansmp.enderchest.rows.1" }))
        assertEquals(2, service.unlockedRows(EnderChestPermissionCheck { it.endsWith(".2") }))
    }

    @Test
    fun `stale writes are rejected and cannot replace newer contents`() {
        val uuid = UUID.randomUUID()
        val initial = EnderChestStorage.empty(uuid)
        val first = service.save(initial.copy(slots = initial.slots.toMutableList().also {
            it[40] = SerializedEnderChestItem(byteArrayOf(1))
        }), "smp-01").toCompletableFuture().join()
        val stale = initial.copy(slots = initial.slots.toMutableList().also {
            it[41] = SerializedEnderChestItem(byteArrayOf(2))
        })

        val failure = assertFailsWith<CompletionException> {
            service.save(stale, "smp-01").toCompletableFuture().join()
        }
        assertEquals(true, failure.cause is EnderChestStorageConflictException)
        assertEquals(1, service.load(uuid, "smp-01").toCompletableFuture().join().revision)
        assertEquals(SerializedEnderChestItem(byteArrayOf(1)), service.load(uuid, "smp-01").toCompletableFuture().join().slots[40])
        assertNull(service.load(uuid, "smp-01").toCompletableFuture().join().slots[41])
        assertEquals(1, first.revision)
    }

    private class InMemoryEnderChestRepository : EnderChestRepository {
        private val values = mutableMapOf<UUID, EnderChestStorage>()

        override fun load(playerId: UUID, serverId: String): EnderChestStorage = values[playerId] ?: EnderChestStorage.empty(playerId)

        override fun save(storage: EnderChestStorage, serverId: String): EnderChestStorage {
            val current = values[storage.playerId]
            if (current == null) {
                if (storage.revision != 0L) throw EnderChestStorageConflictException(storage.playerId)
            } else if (current.revision != storage.revision) {
                throw EnderChestStorageConflictException(storage.playerId)
            }
            return storage.copy(revision = storage.revision + 1).also { values[storage.playerId] = it }
        }
    }
}
