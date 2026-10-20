package de.titansmp.core.stats

import de.titansmp.api.stats.StatsService
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/** Guards that the public stats contracts stay platform and infrastructure independent. */
class StatsApiBoundaryTest {
    private val forbiddenMarkers = listOf(
        "net/luckperms",
        "io/papermc",
        "org/bukkit",
        "com/velocitypowered",
        "io/lettuce",
        "org/mariadb",
        "com/zaxxer",
        "org/flywaydb",
        "net/dv8tion/jda",
        "java/sql",
    )

    @Test
    fun `stats api classes reference no platform database or infrastructure types`() {
        val location: Path = Path.of(
            StatsService::class.java.protectionDomain.codeSource.location.toURI(),
        )
        val offenders = mutableListOf<String>()
        val classFiles: List<Pair<String, ByteArray>> = if (location.toString().endsWith(".jar")) {
            val jar = java.util.jar.JarFile(location.toFile())
            jar.use {
                it.entries().asSequence()
                    .filter { entry -> !entry.isDirectory && entry.name.startsWith("de/titansmp/api/stats/") && entry.name.endsWith(".class") }
                    .map { entry -> entry.name to it.getInputStream(entry).use { stream -> stream.readBytes() } }
                    .toList()
            }
        } else {
            val packageDir = location.resolve("de/titansmp/api/stats")
            assertTrue(Files.isDirectory(packageDir), "Stats API package must be present")
            Files.list(packageDir).use { paths ->
                paths.filter { it.toString().endsWith(".class") }
                    .map { path -> path.fileName.toString() to Files.readAllBytes(path) }
                    .toList()
            }
        }
        assertTrue(classFiles.isNotEmpty(), "Stats API classes must be available")
        classFiles.forEach { (name, bytes) ->
            val text = String(bytes, Charsets.ISO_8859_1)
            forbiddenMarkers.forEach { marker ->
                if (text.contains(marker)) offenders += "$name: $marker"
            }
        }
        assertTrue(offenders.isEmpty(), "Stats API leaks platform or infrastructure types: $offenders")
    }
}
