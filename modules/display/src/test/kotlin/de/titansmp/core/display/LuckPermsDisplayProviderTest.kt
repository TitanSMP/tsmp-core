package de.titansmp.core.display

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LuckPermsDisplayProviderTest {
    private val player = UUID.randomUUID()

    @Test
    fun `provider returns the luckperms prefix and weight without a clan suffix`() {
        val provider = LuckPermsDisplayProvider(StaticSource(RankSnapshot("[Admin]", 100)))
        val meta = provider.displayMeta(player).toCompletableFuture().join()
        assertEquals(player, meta.playerUuid)
        assertEquals("[Admin]", meta.prefix)
        assertEquals(100, meta.weight)
        assertNull(meta.suffix)
        assertTrue(meta.hasPrefix)
    }

    @Test
    fun `missing prefix becomes empty and never invents a suffix`() {
        val provider = LuckPermsDisplayProvider(StaticSource(RankSnapshot(null, 0)))
        val meta = provider.displayMeta(player).toCompletableFuture().join()
        assertEquals("", meta.prefix)
        assertEquals(0, meta.weight)
        assertNull(meta.suffix)
    }

    @Test
    fun `provider loads a user when no cached snapshot exists`() {
        var loaded = false
        val source = object : RankSource {
            override fun cached(playerUuid: UUID): RankSnapshot? = null
            override fun load(playerUuid: UUID): CompletionStage<RankSnapshot> {
                loaded = true
                return CompletableFuture.completedFuture(RankSnapshot("[Member]", 1))
            }
        }
        val meta = LuckPermsDisplayProvider(source).displayMeta(player).toCompletableFuture().join()
        assertTrue(loaded)
        assertEquals("[Member]", meta.prefix)
        assertEquals(1, meta.weight)
    }

    private class StaticSource(private val snapshot: RankSnapshot) : RankSource {
        override fun cached(playerUuid: UUID): RankSnapshot = snapshot
        override fun load(playerUuid: UUID): CompletionStage<RankSnapshot> = CompletableFuture.completedFuture(snapshot)
    }
}
