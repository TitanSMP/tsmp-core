package de.titansmp.paper.auctionhouse

import de.titansmp.paper.serialization.PaperItemStackCodec
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit

class AuctionItemSerializationTest {
    private lateinit var plugin:AuctionSerializationTestPlugin
    @BeforeEach fun setUp(){MockBukkit.mock();plugin=MockBukkit.load(AuctionSerializationTestPlugin::class.java)}
    @AfterEach fun tearDown(){MockBukkit.unmock()}

    @Test fun `Paper codec preserves item stack metadata and persistent data`() {
        val item=ItemStack(Material.DIAMOND_SWORD)
        val key=NamespacedKey(plugin,"auction_metadata")
        item.itemMeta=item.itemMeta.also { meta->meta.displayName(Component.text("Forged blade"));meta.lore(listOf(Component.text("First lore line"),Component.text("Second lore line")));meta.persistentDataContainer.set(key,PersistentDataType.STRING,"kept");meta.addEnchant(Enchantment.SHARPNESS,5,true) }
        val encoded=PaperItemStackCodec.encode(item)
        assertNotNull(encoded)
        val restored=PaperItemStackCodec.decode(encoded)
        assertNotNull(restored)
        assertEquals(item.type,restored!!.type)
        assertEquals(item.itemMeta.displayName(),restored.itemMeta.displayName())
        assertEquals(item.itemMeta.lore(),restored.itemMeta.lore())
        assertEquals(item.itemMeta.enchants,restored.itemMeta.enchants)
        assertEquals("kept",restored.itemMeta.persistentDataContainer.get(key,PersistentDataType.STRING))
    }
}

open class AuctionSerializationTestPlugin:JavaPlugin()
