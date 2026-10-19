package de.titansmp.paper.serialization

import org.bukkit.inventory.ItemStack

/** Paper-native opaque ItemStack encoding shared by Paper-backed storage features. */
object PaperItemStackCodec {
    fun encode(item: ItemStack?): ByteArray? = if (item == null || item.type.isAir) null else item.serializeAsBytes()
    fun decode(bytes: ByteArray?): ItemStack? = bytes?.let(ItemStack::deserializeBytes)
}
