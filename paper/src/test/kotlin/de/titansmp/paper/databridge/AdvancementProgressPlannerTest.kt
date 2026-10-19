package de.titansmp.paper.databridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AdvancementProgressPlannerTest {
    @Test fun `partial criteria replace target progress without merging`() {
        val plan = AdvancementProgressPlanner.plan(
            storedCriteria = mapOf("a" to true, "b" to false, "c" to false),
            targetCriteria = setOf("a", "b", "c"),
            currentlyAwarded = setOf("b"),
            requirements = listOf(setOf("a"), setOf("b"), setOf("c")),
        )

        assertEquals(setOf("b"), plan.revoke)
        assertEquals(setOf("a"), plan.award)
        assertTrue(!plan.deferred)
    }

    @Test fun `completion restore is deferred when it would complete an advancement`() {
        val plan = AdvancementProgressPlanner.plan(
            storedCriteria = mapOf("a" to true, "b" to true),
            targetCriteria = setOf("a", "b"),
            currentlyAwarded = emptySet(),
            requirements = listOf(setOf("a"), setOf("b")),
        )

        assertTrue(plan.deferred)
        assertTrue(plan.award.isEmpty())
        assertTrue(plan.revoke.isEmpty())
    }

    @Test fun `completed advancement is left untouched to avoid a second completion reward`() {
        val plan = AdvancementProgressPlanner.plan(
            storedCriteria = mapOf("a" to true, "b" to true),
            targetCriteria = setOf("a", "b"),
            currentlyAwarded = setOf("a", "b"),
            requirements = listOf(setOf("a"), setOf("b")),
        )

        assertTrue(!plan.deferred)
        assertTrue(plan.award.isEmpty())
        assertTrue(plan.revoke.isEmpty())
    }

    @Test fun `unmatched custom criteria stay deferred while matching criteria are synchronized`() {
        val plan = AdvancementProgressPlanner.plan(
            storedCriteria = mapOf("a" to true, "removed_by_plugin_update" to true),
            targetCriteria = setOf("a", "b"),
            currentlyAwarded = emptySet(),
            requirements = listOf(setOf("a"), setOf("b")),
        )

        assertTrue(plan.deferred)
        assertEquals(setOf("a"), plan.award)
    }
}
