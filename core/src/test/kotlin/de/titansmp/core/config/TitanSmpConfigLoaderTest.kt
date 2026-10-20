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

    @Test fun `loads teleport settings and configured RTP types`() {
        val file=writeConfig()
        Files.writeString(file,Files.readString(file)+"\nteleport.enabled=true\nteleport.default-cooldown-minutes=45\nteleport.default-home-limit=2\nteleport.tpa.request-expiration-seconds=90\nteleport.rtp.max-attempts=24\nteleport.rtp.server-types=survival,resource\nteleport.clan.default-home-limit=4\n")
        val teleport=TitanSmpConfigLoader().load(file,emptyMap()).teleport
        assertTrue(teleport.enabled)
        assertEquals(45,teleport.defaultCooldownMinutes)
        assertEquals(2,teleport.defaultHomeLimit)
        assertEquals(90L,teleport.requestExpirationSeconds)
        assertEquals(24,teleport.rtpMaxAttempts)
        assertEquals(setOf("survival","resource"),teleport.rtpServerTypes)
        assertEquals(4,teleport.defaultClanHomeLimit)
    }

    @Test fun `loads auction house settings and validates bounds`() {
        val file=writeConfig()
        Files.writeString(file,Files.readString(file)+"\nauction-house.enabled=true\nauction-house.expiration-hours=72\nauction-house.items-per-page=36\nauction-house.search-timeout-seconds=45\nauction-house.default-auction-limit=8\nauction-house.sell-fee-percent=2\nauction-house.aliases=market,trade\n")
        val config=TitanSmpConfigLoader().load(file,emptyMap()).auctionHouse
        assertTrue(config.enabled)
        assertEquals(72L,config.expirationHours)
        assertEquals(36,config.itemsPerPage)
        assertEquals(45L,config.searchTimeoutSeconds)
        assertEquals(8,config.defaultAuctionLimit)
        assertEquals(2,config.sellFeePercent)
        assertEquals(listOf("market","trade"),config.aliases)
    }

    @Test fun `rejects invalid auction configuration`() {
        val file=writeConfig();Files.writeString(file,Files.readString(file)+"\nauction-house.sell-fee-percent=101\n")
        assertFailsWith<IllegalArgumentException>{TitanSmpConfigLoader().load(file,emptyMap())}
    }

    @Test fun `loads configurable shop categories and exact prices`() {
        val file=writeConfig()
        Files.writeString(file,Files.readString(file)+"""
            shop.enabled=true
            shop.categories.food.display-name=Food
            shop.categories.food.icon=COOKED_BEEF
            shop.categories.food.slot=20
            shop.categories.food.items.steak.material=COOKED_BEEF
            shop.categories.food.items.steak.amount=16
            shop.categories.food.items.steak.display-name=Steak
            shop.categories.food.items.steak.slot=10
            shop.categories.food.items.steak.buy-price=100.00
            shop.categories.food.items.steak.sell-price=null
        """.trimIndent())
        val shop=TitanSmpConfigLoader().load(file,emptyMap()).shop
        assertTrue(shop.enabled)
        assertEquals("food",shop.categories.single().id)
        assertEquals("COOKED_BEEF",shop.categories.single().items.single().material)
        assertEquals(16,shop.categories.single().items.single().amount)
        assertEquals(10_000L,shop.categories.single().items.single().buyPrice?.minorUnits)
        assertNull(shop.categories.single().items.single().sellPrice)
    }

    @Test fun `rejects invalid shop item price slot amount and duplicate slots`() {
        val file=writeConfig()
        val base="""
            shop.categories.food.display-name=Food
            shop.categories.food.icon=COOKED_BEEF
            shop.categories.food.slot=20
            shop.categories.food.items.steak.material=COOKED_BEEF
            shop.categories.food.items.steak.amount=16
            shop.categories.food.items.steak.display-name=Steak
            shop.categories.food.items.steak.slot=10
            shop.categories.food.items.steak.buy-price=1.00
        """.trimIndent()
        listOf(
            base.replace("buy-price=1.00","buy-price=-1.00"),
            base.replace("slot=10","slot=45"),
            base.replace("amount=16","amount=0"),
            base+"\nshop.categories.food.items.apple.material=APPLE\nshop.categories.food.items.apple.amount=1\nshop.categories.food.items.apple.display-name=Apple\nshop.categories.food.items.apple.slot=10\nshop.categories.food.items.apple.buy-price=1.00",
        ).forEach { contents ->
            Files.writeString(file,Files.readString(writeConfig())+"\n"+contents)
            assertFailsWith<IllegalArgumentException> { TitanSmpConfigLoader().load(file,emptyMap()) }
        }
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
    fun `loads server transfer types backends and aliases from configuration`() {
        val file = writeConfig()
        Files.writeString(
            file,
            Files.readString(file) + """

                server-transfer.enabled=true
                server-transfer.default-priority=1
                server-transfer.queue.reservation-timeout-ms=7000
                server-transfer.aliases.lobby=lobby
                server-transfer.aliases.hub=lobby
                server-transfer.types.lobby.enabled=true
                server-transfer.types.lobby.max-players=100
                server-transfer.types.smp.enabled=true
                server-transfer.types.smp.max-players=50
                server-transfer.backends.lobby-01.type=lobby
                server-transfer.backends.smp-01.type=smp
            """.trimIndent(),
        )

        val transfer = TitanSmpConfigLoader().load(file, emptyMap()).serverTransfer

        assertTrue(transfer.enabled)
        assertEquals(1, transfer.defaultPriority)
        assertEquals(7_000L, transfer.reservationTimeoutMs)
        assertEquals(listOf("lobby", "smp"), transfer.enabledTypes())
        assertEquals("lobby", transfer.typeForBackend("lobby-01"))
        assertEquals("smp", transfer.typeForBackend("smp-01"))
        assertNull(transfer.typeForBackend("unknown-01"))
        assertEquals("lobby", transfer.aliasTarget("hub"))
    }

    @Test
    fun `rejects a backend that maps to an unknown server type`() {
        val file = writeConfig()
        Files.writeString(
            file,
            Files.readString(file) + """

                server-transfer.enabled=true
                server-transfer.types.lobby.enabled=true
                server-transfer.types.lobby.max-players=100
                server-transfer.backends.lobby-01.type=missing
            """.trimIndent(),
        )

        val failure = assertFailsWith<ConfigurationException> { TitanSmpConfigLoader().load(file, emptyMap()) }
        assertTrue(failure.message!!.contains("unknown server type"))
    }

    @Test
    fun `rejects a backend that maps to a disabled server type`() {
        val file = writeConfig()
        Files.writeString(
            file,
            Files.readString(file) + """

                server-transfer.enabled=true
                server-transfer.types.lobby.enabled=false
                server-transfer.types.lobby.max-players=100
                server-transfer.backends.lobby-01.type=lobby
            """.trimIndent(),
        )

        val failure = assertFailsWith<ConfigurationException> { TitanSmpConfigLoader().load(file, emptyMap()) }
        assertTrue(failure.message!!.contains("disabled server type"))
    }

    @Test
    fun `rejects a server type without a capacity`() {
        val file = writeConfig()
        Files.writeString(
            file,
            Files.readString(file) + """

                server-transfer.enabled=true
                server-transfer.types.lobby.enabled=true
            """.trimIndent(),
        )

        val failure = assertFailsWith<ConfigurationException> { TitanSmpConfigLoader().load(file, emptyMap()) }
        assertTrue(failure.message!!.contains("max-players"))
    }

    @Test
    fun `rejects an alias for an unknown server type`() {
        val file = writeConfig()
        Files.writeString(
            file,
            Files.readString(file) + """

                server-transfer.enabled=true
                server-transfer.types.lobby.enabled=true
                server-transfer.types.lobby.max-players=100
                server-transfer.aliases.hub=smp
            """.trimIndent(),
        )

        val failure = assertFailsWith<ConfigurationException> { TitanSmpConfigLoader().load(file, emptyMap()) }
        assertTrue(failure.message!!.contains("unknown server type"))
    }

    @Test
    fun `rejects an invalid server transfer priority and capacity`() {
        val invalidPriority = writeConfig()
        Files.writeString(invalidPriority, Files.readString(invalidPriority) + "\nserver-transfer.default-priority=-1\n")
        assertFailsWith<IllegalArgumentException> { TitanSmpConfigLoader().load(invalidPriority, emptyMap()) }

        val invalidCapacity = writeConfig()
        Files.writeString(
            invalidCapacity,
            Files.readString(invalidCapacity) + """

                server-transfer.enabled=true
                server-transfer.types.lobby.enabled=true
                server-transfer.types.lobby.max-players=0
            """.trimIndent(),
        )
        assertFailsWith<IllegalArgumentException> { TitanSmpConfigLoader().load(invalidCapacity, emptyMap()) }
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
