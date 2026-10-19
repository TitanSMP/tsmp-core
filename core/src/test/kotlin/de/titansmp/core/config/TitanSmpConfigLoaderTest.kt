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
        assertEquals("titansmp", configuration.networkId)
        assertEquals("database-secret", configuration.database.password)
        assertEquals("redis-secret", configuration.redis.password)
        assertTrue(configuration.chat.rateLimitEnabled)
        assertEquals(5, configuration.chat.messagesPerWindow)
        assertEquals(3_000L, configuration.chat.windowMs)
        assertEquals(256, configuration.chat.maxMessageLength)
        assertFalse(configuration.toString().contains("database-secret"))
        assertFalse(configuration.toString().contains("redis-secret"))
        assertFalse(configuration.database.toString().contains("database-secret"))
        assertFalse(configuration.redis.toString().contains("redis-secret"))
    }

    @Test fun `loads chat throttling and length settings`() {
        val file = writeConfig()
        Files.writeString(file, Files.readString(file) + "\nchat.rate-limit.enabled=false\nchat.rate-limit.messages-per-window=8\nchat.rate-limit.window-ms=4500\nchat.max-message-length=180\n")

        val chat = TitanSmpConfigLoader().load(file, emptyMap()).chat

        assertFalse(chat.rateLimitEnabled)
        assertEquals(8, chat.messagesPerWindow)
        assertEquals(4_500L, chat.windowMs)
        assertEquals(180, chat.maxMessageLength)
    }

    @Test fun `rejects invalid chat configuration`() {
        val file = writeConfig()
        Files.writeString(file, Files.readString(file) + "\nchat.rate-limit.enabled=maybe\n")
        assertFailsWith<ConfigurationException> { TitanSmpConfigLoader().load(file, emptyMap()) }
    }

    @Test
    fun `discord integration is disabled by default and needs no credentials`() {
        val configuration = TitanSmpConfigLoader().load(writeConfig(), emptyMap())

        assertFalse(configuration.discord.enabled)
        assertNull(configuration.discord.token)
        assertNull(configuration.discord.guildId)
        assertNull(configuration.discord.minecraftChatChannelId)
        assertNull(configuration.discord.verifiedRoleId)
        assertEquals(10L, configuration.discord.linking.tokenExpirationMinutes)
        assertEquals(5, configuration.discord.linking.maxAttemptsPerWindow)
        assertEquals(60, configuration.discord.linking.attemptWindowSeconds)
    }

    @Test
    fun `reads discord settings from the file and lets the environment override the token`() {
        val file = writeConfig()
        Files.writeString(
            file,
            Files.readString(file) + "\n" + """
                discord.enabled=true
                discord.token=file-token
                discord.guild-id=111
                discord.minecraft-chat-channel-id=222
                discord.verified-role-id=333
                discord.linking.token-expiration-minutes=20
                discord.linking.max-attempts-per-window=3
                discord.linking.attempt-window-seconds=120
            """.trimIndent() + "\n",
        )

        val configuration = TitanSmpConfigLoader().load(file, mapOf("TITANSMP_DISCORD_TOKEN" to "environment-token"))
        val discord = configuration.discord

        assertTrue(discord.enabled)
        assertEquals("environment-token", discord.token)
        assertEquals(111L, discord.guildId)
        assertEquals(222L, discord.minecraftChatChannelId)
        assertEquals(333L, discord.verifiedRoleId)
        assertEquals(20L, discord.linking.tokenExpirationMinutes)
        assertEquals(3, discord.linking.maxAttemptsPerWindow)
        assertEquals(120, discord.linking.attemptWindowSeconds)

        val botSettings = discord.requireBotSettings()
        assertEquals("environment-token", botSettings.token)
        assertEquals(111L, botSettings.guildId)
        assertEquals(222L, botSettings.minecraftChatChannelId)
        assertEquals(333L, botSettings.verifiedRoleId)
    }

    @Test
    fun `the bot token is never exposed by toString`() {
        val file = writeConfig()
        Files.writeString(file, Files.readString(file) + "\ndiscord.enabled=true\ndiscord.token=super-secret-token\n")

        val configuration = TitanSmpConfigLoader().load(file, emptyMap())

        assertFalse(configuration.toString().contains("super-secret-token"))
        assertFalse(configuration.discord.toString().contains("super-secret-token"))
        assertTrue(configuration.discord.toString().contains("token=<redacted>"))
    }

    @Test
    fun `an enabled discord integration without ids fails cleanly instead of starting half a bot`() {
        val file = writeConfig()
        Files.writeString(file, Files.readString(file) + "\ndiscord.enabled=true\ndiscord.token=token\n")

        val discord = TitanSmpConfigLoader().load(file, emptyMap()).discord
        val failure = assertFailsWith<ConfigurationException> { discord.requireBotSettings() }
        assertTrue(failure.message!!.contains("discord.guild-id"))
    }

    @Test
    fun `requireBotSettings refuses a disabled integration`() {
        val configuration = TitanSmpConfigLoader().load(writeConfig(), emptyMap())

        assertFailsWith<ConfigurationException> { configuration.discord.requireBotSettings() }
    }

    @Test
    fun `rejects invalid discord identifiers`() {
        val file = writeConfig()
        Files.writeString(file, Files.readString(file) + "\ndiscord.enabled=true\ndiscord.guild-id=zero\n")
        assertFailsWith<ConfigurationException> { TitanSmpConfigLoader().load(file, emptyMap()) }
    }

    @Test
    fun `allows redis without a password`() {
        val configuration = TitanSmpConfigLoader().load(writeConfig(), emptyMap())

        assertNull(configuration.redis.password)
    }

    @Test
    fun `loads Redis-only event runtime without database credentials and reads transfer timeout`() {
        val file = temporaryDirectory.resolve("proxy.properties")
        Files.writeString(file, """
            server.id=velocity-01
            network.id=titansmp
            redis.host=redis.internal
            redis.port=6380
            redis.password=secret
            data-bridge.transfer.timeout-ms=7200
        """.trimIndent())

        val configuration = TitanSmpConfigLoader().loadRedisEventConfiguration(file, emptyMap())

        assertEquals("velocity-01", configuration.serverId)
        assertEquals("titansmp", configuration.networkId)
        assertEquals("redis.internal", configuration.redis.host)
        assertEquals(6380, configuration.redis.port)
        assertEquals("secret", configuration.redis.password)
        assertEquals(7_200L, configuration.playerTransferTimeoutMs)
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
                "TITANSMP_NETWORK_ID" to "titan-staging",
                "TITANSMP_DATABASE_HOST" to "db.internal",
                "TITANSMP_DATABASE_NAME" to "titansmp",
                "TITANSMP_DATABASE_USERNAME" to "network-service",
                "TITANSMP_DATABASE_PASSWORD" to "database-secret",
                "TITANSMP_REDIS_HOST" to "redis.internal",
            ),
        )

        assertEquals("proxy-01", configuration.serverIdentity.id)
        assertEquals("titan-staging", configuration.networkId)
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
