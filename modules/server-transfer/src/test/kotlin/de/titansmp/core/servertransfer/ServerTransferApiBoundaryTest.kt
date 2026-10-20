package de.titansmp.core.servertransfer

import de.titansmp.api.servertransfer.ServerTransferService
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/** Guards that the public server-transfer contracts stay platform independent. */
class ServerTransferApiBoundaryTest {
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
    )

    @Test
    fun `server transfer api classes reference no platform or infrastructure types`() {
        val location: Path = Path.of(
            ServerTransferService::class.java.protectionDomain.codeSource.location.toURI(),
        )
        val offenders = mutableListOf<String>()
        val classFiles: List<Pair<String, ByteArray>> = if (location.toString().endsWith(".jar")) {
            val jar = java.util.jar.JarFile(location.toFile())
            jar.use {
                it.entries().asSequence()
                    .filter { entry -> !entry.isDirectory && entry.name.startsWith("de/titansmp/api/servertransfer/") && entry.name.endsWith(".class") }
                    .map { entry -> entry.name to it.getInputStream(entry).use { stream -> stream.readBytes() } }
                    .toList()
            }
        } else {
            val packageDir = location.resolve("de/titansmp/api/servertransfer")
            assertTrue(Files.isDirectory(packageDir), "Server transfer API package must be present")
            Files.list(packageDir).use { paths ->
                paths.filter { it.toString().endsWith(".class") }
                    .map { path -> path.fileName.toString() to Files.readAllBytes(path) }
                    .toList()
            }
        }
        assertTrue(classFiles.isNotEmpty(), "Server transfer API classes must be available")
        classFiles.forEach { (name, bytes) ->
            val text = String(bytes, Charsets.ISO_8859_1)
            forbiddenMarkers.forEach { marker ->
                if (text.contains(marker)) offenders += "$name: $marker"
            }
        }
        assertTrue(offenders.isEmpty(), "Server transfer API leaks platform types: $offenders")
    }
}
