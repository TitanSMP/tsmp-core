package de.titansmp.paper.databridge

import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.advancement.Advancement
import org.bukkit.advancement.AdvancementProgress
import org.bukkit.entity.Player

/** Thin Paper boundary that keeps the lifecycle adapter testable without a running advancement registry. */
interface PaperAdvancementAccess {
    fun all(): Iterator<Advancement>
    fun find(key: NamespacedKey): Advancement?
    fun progress(player: Player, advancement: Advancement): AdvancementProgress
}

object BukkitPaperAdvancementAccess : PaperAdvancementAccess {
    override fun all(): Iterator<Advancement> = Bukkit.advancementIterator()
    override fun find(key: NamespacedKey): Advancement? = Bukkit.getAdvancement(key)
    override fun progress(player: Player, advancement: Advancement): AdvancementProgress = player.getAdvancementProgress(advancement)
}
