package de.titansmp.paper.serialization

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.MapMeta
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit

class PaperMapItemSerializationTest {
    @BeforeEach
    fun setUp() {
        MockBukkit.mock()
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    @Test
    fun `filled map item and supported item metadata round-trip through shared codec`() {
        val original = ItemStack(Material.FILLED_MAP).apply {
            itemMeta = (itemMeta as MapMeta).apply {
                setScaling(true)
            }
        }

        val restored = PaperItemStackCodec.decode(PaperItemStackCodec.encode(original))!!
        val restoredMeta = restored.itemMeta as MapMeta

        assertEquals(Material.FILLED_MAP, restored.type)
        assertTrue(!restoredMeta.hasMapId())
        assertTrue(!restoredMeta.hasMapView())
        assertTrue(restoredMeta.isScaling)
    }
}
