package de.titansmp.core.display

import de.titansmp.api.display.PlayerDisplayMeta
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class TabListOrderTest {
    @Test
    fun `higher weight sorts first and equal weight sorts by name`() {
        val owner = row("owner", "[Owner]", 100)
        val adminB = row("Bravo", "[Admin]", 50)
        val adminA = row("alpha", "[Admin]", 50)
        val member = row("Member", "[Member]", 1)

        val ordered = listOf(adminB, member, owner, adminA).sortedWith(TAB_LIST_ORDER)
        assertEquals(listOf(owner, adminA, adminB, member), ordered)
    }

    @Test
    fun `name ordering is case insensitive`() {
        val upper = row("Zed", "", 10)
        val lower = row("alpha", "", 10)
        val mixed = row("Bravo", "", 10)

        val ordered = listOf(upper, mixed, lower).sortedWith(TAB_LIST_ORDER)
        assertEquals(listOf(lower, mixed, upper), ordered)
    }

    @Test
    fun `weight is the only rank input and is never hardcoded`() {
        val low = row("Low", "[Low]", 0)
        val high = row("High", "[High]", 9_999)
        assertEquals(listOf(high, low), listOf(low, high).sortedWith(TAB_LIST_ORDER))
    }

    private fun row(name: String, prefix: String, weight: Int): TabListRow =
        TabListRow(UUID.randomUUID(), name, PlayerDisplayMeta(UUID.randomUUID(), prefix, null, weight))
}
