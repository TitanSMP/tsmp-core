package de.titansmp.core.config

import de.titansmp.api.server.ServerEnvironment
import de.titansmp.api.server.ServerType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.io.TempDir

class TitanSmpConfigLoaderTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `loads typed config and lets environment variables override file values`() {
        val configFile = writeConfig()

        val configuration = TitanSmpConfigLoader().load(
            configFile,
            mapOf(
                "TITANSMP_SERVER_ID" to "smp-override",
                "TITANSMP_DATABASE_PASSWORD" to "database-secret",
                "TITANSMP_REDIS_PASSWORD" to "redis-secret",
            ),
        )

        assertEquals("smp-override", configuration.serverIdentity.id)
        assertEquals(ServerType.SMP, configuration.serverIdentity.type)
        assertEquals(ServerEnvironment.DEVELOPMENT, configuration.serverIdentity.environment)
        assertEquals("TitanSMP", configuration.networkName)
        assertEquals("database-secret", configuration.database.password)
        assertEquals("redis-secret", configuration.redis.password)
        assertFalse(configuration.toString().contains("database-secret"))
        assertFalse(configuration.toString().contains("redis-secret"))
        assertFalse(configuration.database.toString().contains("database-secret"))
        assertFalse(configuration.redis.toString().contains("redis-secret"))
    }

    @Test
    fun `allows redis without a password`() {
        val configuration = TitanSmpConfigLoader().load(writeConfig(), emptyMap())

        assertNull(configuration.redis.password)
    }

    @Test
    fun `can load production configuration entirely from environment variables`() {
        val missingFile = temporaryDirectory.resolve("production.properties")

        val configuration = TitanSmpConfigLoader().load(
            missingFile,
            mapOf(
                "TITANSMP_SERVER_ID" to "proxy-01",
                "TITANSMP_SERVER_TYPE" to "LOBBY",
                "TITANSMP_ENVIRONMENT" to "PRODUCTION",
                "TITANSMP_NETWORK_NAME" to "TitanSMP",
                "TITANSMP_DATABASE_HOST" to "db.internal",
                "TITANSMP_DATABASE_NAME" to "titansmp",
                "TITANSMP_DATABASE_USERNAME" to "network-service",
                "TITANSMP_DATABASE_PASSWORD" to "database-secret",
                "TITANSMP_REDIS_HOST" to "redis.internal",
            ),
        )

        assertEquals("proxy-01", configuration.serverIdentity.id)
        assertEquals("db.internal", configuration.database.host)
        assertEquals("redis.internal", configuration.redis.host)
        assertEquals(3306, configuration.database.port)
        assertEquals(6379, configuration.redis.port)
    }

    @Test
    fun `reports missing configuration without exposing values`() {
        val configFile = temporaryDirectory.resolve("empty.properties")
        Files.writeString(configFile, "database.password=do-not-leak\n")

        val exception = assertFailsWith<ConfigurationException> {
            TitanSmpConfigLoader().load(configFile, emptyMap())
        }

        assertTrue(exception.message!!.contains("server.id"))
        assertFalse(exception.message!!.contains("do-not-leak"))
    }

    private fun writeConfig(): Path {
        val configFile = temporaryDirectory.resolve("titansmp.properties")
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
            """.trimIndent(),
        )
        return configFile
    }
}
