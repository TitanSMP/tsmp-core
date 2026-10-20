package de.titansmp.core.teleport

import kotlin.test.Test
import kotlin.test.assertEquals
import java.util.UUID
import de.titansmp.api.teleport.TeleportCooldownCategory

class TeleportPermissionResolverTest {
    @Test fun `cooldown defaults and highest valid numeric node wins`() {
        assertEquals(30, TeleportPermissionResolver.cooldownMinutes(emptyList(),30))
        assertEquals(60, TeleportPermissionResolver.cooldownMinutes(listOf("titansmp.teleport.cooldown.20","titansmp.teleport.cooldown.60","titansmp.teleport.cooldown.bad"),30))
    }
    @Test fun `home limit chooses maximum and zero means unlimited`() {
        assertEquals(1,TeleportPermissionResolver.homeLimit(emptyList(),1))
        assertEquals(3,TeleportPermissionResolver.homeLimit(listOf("titansmp.teleport.homes.1","titansmp.teleport.homes.3"),1))
        assertEquals(0,TeleportPermissionResolver.homeLimit(listOf("titansmp.teleport.homes.0","titansmp.teleport.homes.3"),1))
    }
    @Test fun `clan capacity is maximum of admin limits and never sums`() {
        assertEquals(3,TeleportPermissionResolver.clanHomeLimit(listOf(listOf("titansmp.teleport.clan.homes.3"),listOf("titansmp.teleport.clan.homes.3")),1))
        assertEquals(10,TeleportPermissionResolver.clanHomeLimit(listOf(listOf("titansmp.teleport.clan.homes.3"),listOf("titansmp.teleport.clan.homes.10")),1))
        assertEquals(0,TeleportPermissionResolver.clanHomeLimit(listOf(listOf("titansmp.teleport.clan.homes.0")),1))
    }
    @Test fun `cooldown keys isolate categories and normalize each home scope`() {
        val id=UUID.fromString("14e9cf91-3d3b-4e26-95b8-0da346659928")
        assertEquals(TeleportCooldownKeys.key(id,TeleportCooldownCategory.HOME,"base"),TeleportCooldownKeys.key(id,TeleportCooldownCategory.HOME,"Base"))
        assertEquals(false,TeleportCooldownKeys.key(id,TeleportCooldownCategory.HOME,"base")==TeleportCooldownKeys.key(id,TeleportCooldownCategory.HOME,"farm"))
        assertEquals(false,TeleportCooldownKeys.key(id,TeleportCooldownCategory.HOME,"base")==TeleportCooldownKeys.key(id,TeleportCooldownCategory.RTP))
        assertEquals(false,TeleportCooldownKeys.key(id,TeleportCooldownCategory.TPA)==TeleportCooldownKeys.key(id,TeleportCooldownCategory.TPAHERE))
        assertEquals(false,TeleportCooldownKeys.key(id,TeleportCooldownCategory.CLAN_HOME,"base")==TeleportCooldownKeys.key(id,TeleportCooldownCategory.HOME,"base"))
    }
}
