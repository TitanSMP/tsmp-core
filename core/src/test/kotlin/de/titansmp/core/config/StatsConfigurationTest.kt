package de.titansmp.core.config

import de.titansmp.api.stats.StatType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.io.TempDir

class StatsConfigurationTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `statistics are disabled by default with no tracking enabled`() {
        val stats = load("")
        assertFalse(stats.enabled)
        StatType.entries.forEach { assertFalse(stats.trackingFor(it).enabled, "$it should default to disabled") }
        assertEquals(5_000L, stats.flushIntervalMs)
    }

    @Test
    fun `enabled statistics parse scope and server types`() {
        val stats = load(
            """
            stats.enabled=true
            stats.tracking.playtime.enabled=true
            stats.tracking.playtime.scope=server-types
            stats.tracking.playtime.server-types=smp
            stats.tracking.joins.enabled=true
            stats.tracking.joins.scope=network
            """,
        )

        assertTrue(stats.enabled)
        assertTrue(stats.trackingFor(StatType.PLAYTIME).enabled)
        assertEquals(setOf("smp"), stats.trackingFor(StatType.PLAYTIME).serverTypes)
        assertEquals(StatScope.SERVER_TYPES, stats.trackingFor(StatType.PLAYTIME).scope)
        assertEquals(StatScope.NETWORK, stats.trackingFor(StatType.JOINS).scope)
    }

    @Test
    fun `server-type filtering distinguishes smp from lobby and is case-insensitive`() {
        val stats = load(
            """
            stats.enabled=true
            stats.tracking.blocks-mined.enabled=true
            stats.tracking.blocks-mined.scope=server-types
            stats.tracking.blocks-mined.server-types=smp
            """,
        )

        assertTrue(stats.isTrackedOn(StatType.BLOCKS_MINED, "SMP"))
        assertTrue(stats.isTrackedOn(StatType.BLOCKS_MINED, "smp"))
        assertFalse(stats.isTrackedOn(StatType.BLOCKS_MINED, "LOBBY"))
        assertFalse(stats.isTrackedOn(StatType.BLOCKS_MINED, "BEDWARS"))
    }

    @Test
    fun `network scope tracks on every server including lobby`() {
        val stats = load(
            """
            stats.enabled=true
            stats.tracking.joins.enabled=true
            stats.tracking.joins.scope=network
            """,
        )

        assertTrue(stats.isTrackedOn(StatType.JOINS, "LOBBY"))
        assertTrue(stats.isTrackedOn(StatType.JOINS, "SMP"))
    }

    @Test
    fun `a disabled statistic is tracked nowhere even when a type matches`() {
        val stats = load(
            """
            stats.enabled=true
            stats.tracking.playtime.enabled=false
            stats.tracking.playtime.scope=server-types
            stats.tracking.playtime.server-types=smp
            """,
        )

        assertFalse(stats.isTrackedOn(StatType.PLAYTIME, "SMP"))
    }

    @Test
    fun `multiple server types are accepted and whitespace is trimmed`() {
        val stats = load(
            """
            stats.enabled=true
            stats.tracking.damage-taken.enabled=true
            stats.tracking.damage-taken.scope=server-types
            stats.tracking.damage-taken.server-types=smp, bedwars
            """,
        )

        assertEquals(setOf("smp", "bedwars"), stats.trackingFor(StatType.DAMAGE_TAKEN).serverTypes)
        assertTrue(stats.isTrackedOn(StatType.DAMAGE_TAKEN, "BEDWARS"))
    }

    @Test
    fun `an unknown server type is rejected`() {
        val failure = assertFailsWith<ConfigurationException> {
            load(
                """
                stats.enabled=true
                stats.tracking.playtime.enabled=true
                stats.tracking.playtime.scope=server-types
                stats.tracking.playtime.server-types=not-a-real-type
                """,
            )
        }
        assertTrue(failure.message!!.contains("unknown server type"))
    }

    @Test
    fun `an enabled server-types statistic without any server type is rejected`() {
        val failure = assertFailsWith<ConfigurationException> {
            load(
                """
                stats.enabled=true
                stats.tracking.playtime.enabled=true
                stats.tracking.playtime.scope=server-types
                """,
            )
        }
        assertTrue(failure.message!!.contains("must list at least one server type"))
    }

    @Test
    fun `an unknown statistic name is rejected`() {
        assertFailsWith<ConfigurationException> {
            load("stats.tracking.not-a-stat.enabled=true")
        }
    }

    @Test
    fun `an invalid scope is rejected`() {
        assertFailsWith<ConfigurationException> {
            load(
                """
                stats.enabled=true
                stats.tracking.joins.enabled=true
                stats.tracking.joins.scope=sometimes
                """,
            )
        }
    }

    @Test
    fun `an invalid boolean is rejected`() {
        assertFailsWith<ConfigurationException> { load("stats.enabled=maybe") }
    }

    @Test
    fun `the flush interval is validated`() {
        assertFailsWith<IllegalArgumentException> { StatsConfig(flushIntervalMs = 10) }
        assertFailsWith<IllegalArgumentException> { StatsConfig(flushIntervalMs = 1_000_000) }
    }

    private fun load(extra: String): StatsConfig {
        val configFile = temporaryDirectory.resolve("titansmp-stats.properties")
        Files.writeString(
            configFile,
            """
                server.id=smp-01
                server.type=SMP
                server.environment=DEVELOPMENT
                network.name=TitanSMP
                database.host=127.0.0.1
                database.port=3306
                database.name=titansmp
                database.username=local-user
                database.password=file-database-secret
                database.maximumPoolSize=4
                redis.host=127.0.0.1
                redis.port=6379
                redis.password=
            """.trimIndent() + "\n" + extra,
        )
        return TitanSmpConfigLoader().load(configFile, emptyMap()).stats
    }
}
