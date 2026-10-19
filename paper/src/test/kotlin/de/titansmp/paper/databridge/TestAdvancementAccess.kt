package de.titansmp.paper.databridge

import org.bukkit.NamespacedKey
import org.bukkit.advancement.Advancement
import org.bukkit.advancement.AdvancementProgress
import org.bukkit.advancement.AdvancementRequirement
import org.bukkit.advancement.AdvancementRequirements
import org.bukkit.entity.Player
import java.lang.reflect.Proxy

internal class TestAdvancementAccess : PaperAdvancementAccess {
    val key = NamespacedKey.fromString("titansmp:test")!!
    val criteria = setOf("a", "b", "c")
    val awarded = mutableSetOf<String>()
    private val requirementList = criteria.map { criterion ->
        Proxy.newProxyInstance(AdvancementRequirement::class.java.classLoader, arrayOf(AdvancementRequirement::class.java)) { _, method, _ ->
            when (method.name) {
                "getRequiredCriteria" -> listOf(criterion)
                "isStrict" -> true
                "toString" -> "Requirement($criterion)"
                "hashCode" -> criterion.hashCode()
                "equals" -> false
                else -> null
            }
        } as AdvancementRequirement
    }
    private val requirements = Proxy.newProxyInstance(
        AdvancementRequirements::class.java.classLoader, arrayOf(AdvancementRequirements::class.java),
    ) { _, method, _ -> if (method.name == "getRequirements") requirementList else null } as AdvancementRequirements
    private val advancement = Proxy.newProxyInstance(Advancement::class.java.classLoader, arrayOf(Advancement::class.java)) { _, method, _ ->
        when (method.name) {
            "getKey" -> key
            "getCriteria" -> criteria
            "getRequirements" -> requirements
            "toString" -> key.toString()
            "hashCode" -> key.hashCode()
            "equals" -> false
            else -> null
        }
    } as Advancement
    private val progress = Proxy.newProxyInstance(AdvancementProgress::class.java.classLoader, arrayOf(AdvancementProgress::class.java)) { _, method, args ->
        when (method.name) {
            "getAdvancement" -> advancement
            "isDone" -> requirements.requirements.all { requirement -> requirement.requiredCriteria.any { it in awarded } }
            "getAwardedCriteria" -> awarded.toSet()
            "getRemainingCriteria" -> criteria - awarded
            "awardCriteria" -> awarded.add(args!![0] as String)
            "revokeCriteria" -> awarded.remove(args!![0] as String)
            "getDateAwarded" -> null
            "toString" -> "Progress($awarded)"
            "hashCode" -> System.identityHashCode(this)
            "equals" -> false
            else -> null
        }
    } as AdvancementProgress

    override fun all(): Iterator<Advancement> = listOf(advancement).iterator()
    override fun find(key: NamespacedKey): Advancement? = advancement.takeIf { it.key == key }
    override fun progress(player: Player, advancement: Advancement): AdvancementProgress = progress
}
