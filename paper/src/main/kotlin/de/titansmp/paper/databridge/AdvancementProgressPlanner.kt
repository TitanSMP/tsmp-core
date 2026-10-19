package de.titansmp.paper.databridge

/** Plans exact criterion changes without causing a not-done -> done transition (and its rewards). */
internal object AdvancementProgressPlanner {
    data class Plan(val revoke: Set<String>, val award: Set<String>, val deferred: Boolean)

    fun plan(
        storedCriteria: Map<String, Boolean>,
        targetCriteria: Set<String>,
        currentlyAwarded: Set<String>,
        requirements: List<Set<String>>,
    ): Plan {
        val desired = targetCriteria.filterTo(mutableSetOf()) { storedCriteria[it] == true }
        val desiredDone = requirements.all { requirement -> requirement.any { it in desired } }
        val currentDone = requirements.all { requirement -> requirement.any { it in currentlyAwarded } }
        val definitionMismatch = storedCriteria.keys.any { it !in targetCriteria }
        if (desiredDone && (!currentDone || currentlyAwarded != desired)) {
            return Plan(emptySet(), emptySet(), deferred = true)
        }
        return Plan(currentlyAwarded - desired, desired - currentlyAwarded, definitionMismatch)
    }
}
