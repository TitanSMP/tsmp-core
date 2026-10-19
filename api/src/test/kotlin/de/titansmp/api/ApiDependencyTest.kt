package de.titansmp.api

import de.titansmp.api.display.PlayerDisplayMeta
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards the `api` module contract: it must only contain platform-independent contracts and must never
 * reference Paper, Velocity, LuckPerms, Redis, MariaDB, Flyway, PlaceholderAPI, or JDA.
 */
class ApiDependencyTest {
    private val forbiddenMarkers = listOf(
        "net/luckperms",       // LuckPerms
        "io/papermc",          // Paper
        "org/bukkit",          // Bukkit
        "com/velocitypowered", // Velocity
        "io/lettuce",          // Redis
        "org/mariadb",         // MariaDB
        "com/zaxxer",           // HikariCP
        "org/flywaydb",        // Flyway
        "me/clip",             // PlaceholderAPI
        "net/dv8tion/jda",     // JDA (Discord)
    )

    @Test
    fun `api classes reference no platform or infrastructure types`() {
        val classesRoot: Path = Path.of(PlayerDisplayMeta::class.java.protectionDomain.codeSource.location.toURI())
        val violations = mutableListOf<String>()
        Files.walk(classesRoot).use { stream ->
            stream.filter { it.toString().endsWith(".class") }.forEach { classFile ->
                val bytecode = String(Files.readAllBytes(classFile), Charsets.ISO_8859_1)
                forbiddenMarkers.forEach { marker ->
                    if (bytecode.contains(marker)) violations += "${classFile.fileName} -> $marker"
                }
            }
        }
        assertTrue(violations.isEmpty(), "api must not reference forbidden dependencies: $violations")
    }
}
