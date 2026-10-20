package de.titansmp.core.servertransfer

import kotlin.test.Test
import kotlin.test.assertEquals

class QueuePriorityTest {
    @Test
    fun `parses numeric priority permissions`() {
        val priority = QueuePriorityPermissions.highest(
            mapOf("titansmp.server.priority.1" to true, "titansmp.server.priority.2" to true),
        )
        assertEquals(2, priority)
    }

    @Test
    fun `highest valid priority wins`() {
        val priority = QueuePriorityPermissions.highest(
            mapOf(
                "titansmp.server.priority.1" to true,
                "titansmp.server.priority.3" to true,
                "titansmp.server.priority.2" to false,
            ),
        )
        assertEquals(3, priority)
    }

    @Test
    fun `unset priority permission uses the configured default`() {
        assertEquals(7, QueuePriorityPermissions.highest(emptyMap(), defaultPriority = 7))
    }

    @Test
    fun `denied and malformed priority nodes are ignored`() {
        val priority = QueuePriorityPermissions.highest(
            mapOf(
                "titansmp.server.priority.1" to true,
                "titansmp.server.priority.2" to false,
                "titansmp.server.priority.vip" to true,
                "titansmp.server.priority" to true,
                "titansmp.server.priority.-1" to true,
                "other.permission" to true,
            ),
        )
        assertEquals(1, priority)
    }
}
