package de.titansmp.core.config

import de.titansmp.api.server.ServerIdentity
import de.titansmp.api.server.ServerType
import de.titansmp.api.stats.StatType
import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.util.Properties

class TitanSmpConfigLoader {
    fun loadRedisEventConfiguration(
        path: Path = Path.of("config", "titansmp.properties"),
        environment: Map<String, String> = System.getenv(),
    ): RedisEventConfiguration {
        val properties = Properties()
        if (Files.isRegularFile(path)) Files.newBufferedReader(path, StandardCharsets.UTF_8).use(properties::load)
        fun configured(property: String, variable: String, default: String? = null): String =
            environment[variable]?.takeIf(String::isNotBlank)?.trim()
                ?: properties.getProperty(property)?.takeIf(String::isNotBlank)?.trim()
                ?: default
                ?: throw ConfigurationException("Missing required configuration '$property' (or '$variable')")
        val networkName = configured("network.name", "TITANSMP_NETWORK_NAME", "TitanSMP")
        val derivedNetworkId = networkName.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-').ifBlank { "titansmp" }
        val timeout = configured("data-bridge.transfer.timeout-ms", "TITANSMP_TRANSFER_TIMEOUT_MS", "5000").toLongOrNull()
            ?: throw ConfigurationException("Invalid integer for 'data-bridge.transfer.timeout-ms'")
        return RedisEventConfiguration(
            serverId = configured("server.id", "TITANSMP_SERVER_ID", "velocity"),
            networkId = configured("network.id", "TITANSMP_NETWORK_ID", derivedNetworkId),
            redis = RedisConfig(
                configured("redis.host", "TITANSMP_REDIS_HOST", "127.0.0.1"),
                configured("redis.port", "TITANSMP_REDIS_PORT", "6379").toIntOrNull()
                    ?: throw ConfigurationException("Invalid integer for 'redis.port'"),
                (environment["TITANSMP_REDIS_PASSWORD"] ?: properties.getProperty("redis.password"))?.takeIf(String::isNotBlank),
            ),
            playerTransferTimeoutMs = timeout,
        )
    }

    fun load(
        path: Path = Path.of("config", "titansmp.properties"),
        environment: Map<String, String> = System.getenv(),
    ): TitanSmpConfig {
        if (Files.exists(path) && !Files.isRegularFile(path)) {
            throw ConfigurationException("Configuration path '$path' is not a regular file")
        }

        val properties = Properties()
        if (Files.isRegularFile(path)) {
            Files.newBufferedReader(path, StandardCharsets.UTF_8).use(properties::load)
        }

        fun value(key: String, variable: String, default: String? = null): String =
            environment[variable]?.takeIf(String::isNotBlank)?.trim()
                ?: properties.getProperty(key)?.takeIf(String::isNotBlank)?.trim()
                ?: default
                ?: throw ConfigurationException("Missing required configuration '$key' (or '$variable')")

        fun integer(key: String, variable: String, default: Int): Int =
            value(key, variable, default.toString()).toIntOrNull()
                ?: throw ConfigurationException("Invalid integer for '$key' (or '$variable')")

        fun optional(key: String, variable: String): String? =
            environment[variable]?.takeIf(String::isNotBlank)?.trim()
                ?: properties.getProperty(key)?.takeIf(String::isNotBlank)?.trim()

        fun optionalLong(key: String, variable: String): Long? {
            val raw = optional(key, variable) ?: return null
            return raw.toLongOrNull() ?: throw ConfigurationException("Invalid integer for '$key' (or '$variable')")
        }

        fun longValue(key: String, variable: String, default: Long): Long {
            val raw = optional(key, variable) ?: return default
            return raw.toLongOrNull() ?: throw ConfigurationException("Invalid integer for '$key' (or '$variable')")
        }

        val identity = ServerIdentity(
            id = value("server.id", "TITANSMP_SERVER_ID"),
            type = value("server.type", "TITANSMP_SERVER_TYPE").toServerType("server.type"),
            environment = value("server.environment", "TITANSMP_ENVIRONMENT")
                .toServerEnvironment("server.environment"),
        )
        val redisPassword = environment["TITANSMP_REDIS_PASSWORD"]
            ?: properties.getProperty("redis.password")

        val networkName = value("network.name", "TITANSMP_NETWORK_NAME")
        val defaultNetworkId = networkName.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-').ifBlank { "titansmp" }
        return TitanSmpConfig(
            serverIdentity = identity,
            networkName = networkName,
            networkId = value("network.id", "TITANSMP_NETWORK_ID", defaultNetworkId),
            database = DatabaseConfig(
                host = value("database.host", "TITANSMP_DATABASE_HOST"),
                port = integer("database.port", "TITANSMP_DATABASE_PORT", 3306),
                database = value("database.name", "TITANSMP_DATABASE_NAME"),
                username = value("database.username", "TITANSMP_DATABASE_USERNAME"),
                password = value("database.password", "TITANSMP_DATABASE_PASSWORD"),
                maximumPoolSize = integer("database.maximumPoolSize", "TITANSMP_DATABASE_MAXIMUM_POOL_SIZE", 10),
            ),
            redis = RedisConfig(
                host = value("redis.host", "TITANSMP_REDIS_HOST"),
                port = integer("redis.port", "TITANSMP_REDIS_PORT", 6379),
                password = redisPassword?.takeIf(String::isNotBlank),
            ),
            playerTransferTimeoutMs = integer("data-bridge.transfer.timeout-ms", "TITANSMP_TRANSFER_TIMEOUT_MS", 5_000).toLong(),
            chat = ChatConfig(
                rateLimitEnabled = value("chat.rate-limit.enabled", "TITANSMP_CHAT_RATE_LIMIT_ENABLED", "true").toBooleanStrictOrConfigError("chat.rate-limit.enabled"),
                messagesPerWindow = integer("chat.rate-limit.messages-per-window", "TITANSMP_CHAT_MESSAGES_PER_WINDOW", 5),
                windowMs = value("chat.rate-limit.window-ms", "TITANSMP_CHAT_WINDOW_MS", "3000").toLongOrNull()
                    ?: throw ConfigurationException("Invalid integer for 'chat.rate-limit.window-ms'"),
                maxMessageLength = integer("chat.max-message-length", "TITANSMP_CHAT_MAX_MESSAGE_LENGTH", 256),
            ),
            discord = DiscordConfig(
                enabled = value("discord.enabled", "TITANSMP_DISCORD_ENABLED", "false").toBooleanStrictOrConfigError("discord.enabled"),
                // The bot token is a secret and only read from the environment or the local config file.
                token = optional("discord.token", "TITANSMP_DISCORD_TOKEN"),
                guildId = optionalLong("discord.guild-id", "TITANSMP_DISCORD_GUILD_ID"),
                minecraftChatChannelId = optionalLong("discord.minecraft-chat-channel-id", "TITANSMP_DISCORD_CHAT_CHANNEL_ID"),
                verifiedRoleId = optionalLong("discord.verified-role-id", "TITANSMP_DISCORD_VERIFIED_ROLE_ID"),
                linking = DiscordLinkingConfig(
                    tokenExpirationMinutes = longValue("discord.linking.token-expiration-minutes", "TITANSMP_DISCORD_LINK_TOKEN_EXPIRATION_MINUTES", 10),
                    maxAttemptsPerWindow = integer("discord.linking.max-attempts-per-window", "TITANSMP_DISCORD_LINK_MAX_ATTEMPTS_PER_WINDOW", 5),
                    attemptWindowSeconds = integer("discord.linking.attempt-window-seconds", "TITANSMP_DISCORD_LINK_ATTEMPT_WINDOW_SECONDS", 60),
                ),
            ),
            serverTransfer = loadServerTransfer(properties, environment),
            stats = loadStats(properties, environment),
            friends = FriendsConfig(
                enabled = value("friends.enabled", "TITANSMP_FRIENDS_ENABLED", "true").toBooleanStrictOrConfigError("friends.enabled"),
                pageSize = integer("friends.page-size", "TITANSMP_FRIENDS_PAGE_SIZE", 10),
            ),
            clans = ClansConfig(
                enabled = value("clans.enabled", "TITANSMP_CLANS_ENABLED", "true").toBooleanStrictOrConfigError("clans.enabled"),
                invitationExpirationHours = longValue("clans.invitation-expiration-hours", "TITANSMP_CLAN_INVITATION_EXPIRATION_HOURS", 24),
                pageSize = integer("clans.page-size", "TITANSMP_CLANS_PAGE_SIZE", 10),
                nameMinLength = integer("clans.name-min-length", "TITANSMP_CLAN_NAME_MIN_LENGTH", 3),
                nameMaxLength = integer("clans.name-max-length", "TITANSMP_CLAN_NAME_MAX_LENGTH", 24),
                tagMinLength = integer("clans.tag-min-length", "TITANSMP_CLAN_TAG_MIN_LENGTH", 2),
                tagMaxLength = integer("clans.tag-max-length", "TITANSMP_CLAN_TAG_MAX_LENGTH", 8),
            ),
            teleport = TeleportConfig(
                enabled = value("teleport.enabled", "TITANSMP_TELEPORT_ENABLED", "true").toBooleanStrictOrConfigError("teleport.enabled"),
                defaultCooldownMinutes = integer("teleport.default-cooldown-minutes", "TITANSMP_TELEPORT_DEFAULT_COOLDOWN_MINUTES", 30),
                defaultHomeLimit = integer("teleport.default-home-limit", "TITANSMP_TELEPORT_DEFAULT_HOME_LIMIT", 1),
                requestExpirationSeconds = longValue("teleport.tpa.request-expiration-seconds", "TITANSMP_TELEPORT_TPA_REQUEST_EXPIRATION_SECONDS", 60),
                rtpMaxAttempts = integer("teleport.rtp.max-attempts", "TITANSMP_TELEPORT_RTP_MAX_ATTEMPTS", 20),
                rtpServerTypes = (optional("teleport.rtp.server-types", "TITANSMP_TELEPORT_RTP_SERVER_TYPES") ?: "").split(',').map(String::trim).filter(String::isNotEmpty).map(String::lowercase).toSet(),
                defaultClanHomeLimit = integer("teleport.clan.default-home-limit", "TITANSMP_TELEPORT_CLAN_DEFAULT_HOME_LIMIT", 1),
            ),
            auctionHouse = AuctionHouseConfig(
                enabled = value("auction-house.enabled", "TITANSMP_AUCTION_HOUSE_ENABLED", "true").toBooleanStrictOrConfigError("auction-house.enabled"),
                expirationHours = longValue("auction-house.expiration-hours", "TITANSMP_AUCTION_HOUSE_EXPIRATION_HOURS", 48),
                itemsPerPage = integer("auction-house.items-per-page", "TITANSMP_AUCTION_HOUSE_ITEMS_PER_PAGE", 45),
                searchTimeoutSeconds = longValue("auction-house.search-timeout-seconds", "TITANSMP_AUCTION_HOUSE_SEARCH_TIMEOUT_SECONDS", 30),
                defaultAuctionLimit = integer("auction-house.default-auction-limit", "TITANSMP_AUCTION_HOUSE_DEFAULT_AUCTION_LIMIT", 5),
                sellFeePercent = integer("auction-house.sell-fee-percent", "TITANSMP_AUCTION_HOUSE_SELL_FEE_PERCENT", 0),
                aliases = value("auction-house.aliases", "TITANSMP_AUCTION_HOUSE_ALIASES", "auctionhouse,auction")
                    .split(',').map(String::trim).filter(String::isNotEmpty),
            ),
        )
    }

    private fun loadServerTransfer(
        properties: Properties,
        environment: Map<String, String>,
    ): ServerTransferConfig {
        fun value(key: String, variable: String, default: String): String =
            environment[variable]?.takeIf(String::isNotBlank)?.trim()
                ?: properties.getProperty(key)?.takeIf(String::isNotBlank)?.trim()
                ?: default

        fun integer(key: String, variable: String, default: Int): Int =
            value(key, variable, default.toString()).toIntOrNull()
                ?: throw ConfigurationException("Invalid integer for '$key' (or '$variable')")

        fun longValue(key: String, variable: String, default: Long): Long =
            value(key, variable, default.toString()).toLongOrNull()
                ?: throw ConfigurationException("Invalid integer for '$key' (or '$variable')")

        fun boolean(key: String, variable: String, default: Boolean): Boolean =
            value(key, variable, default.toString()).toBooleanStrictOrConfigError(key)

        val typeProperties = linkedMapOf<String, MutableTypeSettings>()
        val backendProperties = linkedMapOf<String, String>()
        val aliases = linkedMapOf<String, String>()
        val typePrefix = "server-transfer.types."
        val backendPrefix = "server-transfer.backends."
        val aliasPrefix = "server-transfer.aliases."
        val typePattern = Regex("^server-transfer\\.types\\.([a-z0-9][a-z0-9_-]{0,63})\\.(enabled|max-players)$")
        val backendPattern = Regex("^server-transfer\\.backends\\.([A-Za-z0-9_.-]{1,64})\\.type$")
        val aliasPattern = Regex("^server-transfer\\.aliases\\.([a-z0-9][a-z0-9_-]{0,63})$")

        properties.stringPropertyNames().sorted().forEach { key ->
            when {
                key.startsWith(typePrefix) -> {
                    val match = typePattern.matchEntire(key)
                        ?: throw ConfigurationException("Invalid server transfer type property '$key'")
                    val type = match.groupValues[1]
                    val normalized = type.lowercase()
                    if (typeProperties.keys.none { it.equals(normalized, ignoreCase = true) }) {
                        typeProperties[normalized] = MutableTypeSettings()
                    }
                    val settings = typeProperties.getValue(normalized)
                    val raw = properties.getProperty(key).trim()
                    when (match.groupValues[2]) {
                        "enabled" -> settings.enabled = raw.toBooleanStrictOrConfigError(key)
                        "max-players" -> settings.maxPlayers = raw.toIntOrNull()
                            ?: throw ConfigurationException("Invalid integer for '$key'")
                    }
                }
                key.startsWith(backendPrefix) -> {
                    val match = backendPattern.matchEntire(key)
                        ?: throw ConfigurationException("Invalid server transfer backend property '$key'")
                    val backend = match.groupValues[1]
                    if (backendProperties.keys.any { it.equals(backend, ignoreCase = true) }) {
                        throw ConfigurationException("Duplicate backend mapping for '$backend' (backend names are case-insensitive)")
                    }
                    val type = properties.getProperty(key).trim().lowercase()
                    if (type.isBlank()) throw ConfigurationException("Backend '$backend' must reference a server type")
                    backendProperties[backend] = type
                }
                key.startsWith(aliasPrefix) -> {
                    val match = aliasPattern.matchEntire(key)
                        ?: throw ConfigurationException("Invalid server transfer alias property '$key'")
                    val alias = match.groupValues[1]
                    if (aliases.containsKey(alias)) {
                        throw ConfigurationException("Duplicate server transfer alias '$alias'")
                    }
                    val type = properties.getProperty(key).trim().lowercase()
                    if (type.isBlank()) throw ConfigurationException("Alias '$alias' must reference a server type")
                    aliases[alias] = type
                }
            }
        }

        val types = typeProperties.mapValues { (type, settings) ->
            val maxPlayers = settings.maxPlayers
                ?: throw ConfigurationException("Missing 'server-transfer.types.$type.max-players'")
            ServerTransferTypeConfig(settings.enabled, maxPlayers)
        }
        val backends = backendProperties.mapValues { (_, type) -> ServerTransferBackendConfig(type) }

        return ServerTransferConfig(
            enabled = boolean("server-transfer.enabled", "TITANSMP_SERVER_TRANSFER_ENABLED", false),
            defaultPriority = integer("server-transfer.default-priority", "TITANSMP_SERVER_TRANSFER_DEFAULT_PRIORITY", 0),
            actionbarIntervalMs = longValue("server-transfer.queue.actionbar-interval-ms", "TITANSMP_SERVER_TRANSFER_ACTIONBAR_INTERVAL_MS", 1_000),
            reservationTimeoutMs = longValue("server-transfer.queue.reservation-timeout-ms", "TITANSMP_SERVER_TRANSFER_RESERVATION_TIMEOUT_MS", 5_000),
            processingIntervalMs = longValue("server-transfer.queue.processing-interval-ms", "TITANSMP_SERVER_TRANSFER_PROCESSING_INTERVAL_MS", 1_000),
            aliases = aliases,
            types = types,
            backends = backends,
        )
    }

    private data class MutableTypeSettings(
        var enabled: Boolean = true,
        var maxPlayers: Int? = null,
    )

    private fun loadStats(
        properties: Properties,
        environment: Map<String, String>,
    ): StatsConfig {
        fun value(key: String, variable: String, default: String): String =
            environment[variable]?.takeIf(String::isNotBlank)?.trim()
                ?: properties.getProperty(key)?.takeIf(String::isNotBlank)?.trim()
                ?: default

        fun longValue(key: String, variable: String, default: Long): Long =
            value(key, variable, default.toString()).toLongOrNull()
                ?: throw ConfigurationException("Invalid integer for '$key' (or '$variable')")

        fun boolean(key: String, variable: String, default: Boolean): Boolean =
            value(key, variable, default.toString()).toBooleanStrictOrConfigError(key)

        val enabled = boolean("stats.enabled", "TITANSMP_STATS_ENABLED", false)
        val flushIntervalMs = longValue("stats.flush-interval-ms", "TITANSMP_STATS_FLUSH_INTERVAL_MS", 5_000)

        val enabledOverrides = linkedMapOf<StatType, Boolean>()
        val typeLists = linkedMapOf<StatType, MutableSet<String>>()
        val scopeOverrides = linkedMapOf<StatType, StatScope>()
        val trackPattern = Regex("^stats\\.tracking\\.([a-z0-9-]+)\\.(enabled|scope|server-types)$")

        properties.stringPropertyNames().sorted().forEach { key ->
            if (!key.startsWith("stats.tracking.")) return@forEach
            val match = trackPattern.matchEntire(key)
                ?: throw ConfigurationException("Invalid stats tracking property '$key'")
            val statType = match.groupValues[1].toStatType(key)
            when (match.groupValues[2]) {
                "enabled" -> enabledOverrides[statType] = properties.getProperty(key).trim().toBooleanStrictOrConfigError(key)
                "scope" -> scopeOverrides[statType] = properties.getProperty(key).trim().toStatScope(key)
                "server-types" -> {
                    val types = properties.getProperty(key).split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    types.forEach { ensureKnownServerType(it, key) }
                    typeLists.getOrPut(statType) { linkedSetOf() }.addAll(types)
                }
            }
        }

        val tracking = StatType.entries.associateWith { statType ->
            val statScope = scopeOverrides[statType] ?: StatScope.SERVER_TYPES
            val statTypes = typeLists[statType]?.toSet() ?: emptySet()
            val statEnabled = enabledOverrides[statType] ?: false
            if (statEnabled && statScope == StatScope.SERVER_TYPES && statTypes.isEmpty()) {
                throw ConfigurationException(
                    "stats.tracking.${statType.configKey}.server-types must list at least one server type " +
                        "when the statistic is enabled with scope=server-types (or use scope=network)",
                )
            }
            StatTrackingConfig(statEnabled, statScope, statTypes)
        }

        return StatsConfig(enabled = enabled, flushIntervalMs = flushIntervalMs, tracking = tracking)
    }

    /**
     * Rejects a `stats.tracking.<stat>.server-types` entry that is not a known [ServerType].
     *
     * The comparison is configuration-driven and case-insensitive. An unknown identity type is a
     * configuration error so a typo cannot silently drop statistics.
     */
    private fun ensureKnownServerType(rawType: String, key: String) {
        val known = ServerType.entries.any { it.name.equals(rawType, ignoreCase = true) }
        if (!known) {
            throw ConfigurationException(
                "The stats property '$key' references unknown server type '$rawType'",
            )
        }
    }

    private fun String.toStatType(key: String): StatType {
        val normalized = lowercase().replace('-', '_').replace("_", "")
        return StatType.entries.firstOrNull { it.name.lowercase().replace("_", "") == normalized }
            ?: throw ConfigurationException("The stats property '$key' references unknown statistic '$this'")
    }

    private fun String.toStatScope(key: String): StatScope =
        StatScope.entries.firstOrNull { it.name.replace('_', '-').equals(this, ignoreCase = true) }
            ?: throw ConfigurationException("Invalid stats scope '$this' for '$key'; expected network or server-types")
}

private val StatType.configKey: String get() = name.lowercase().replace('_', '-')

private fun String.toBooleanStrictOrConfigError(key: String): Boolean = try {
    toBooleanStrict()
} catch (failure: IllegalArgumentException) {
    throw ConfigurationException("Invalid boolean for '$key'", failure)
}

data class RedisEventConfiguration(
    val serverId: String,
    val networkId: String,
    val redis: RedisConfig,
    val playerTransferTimeoutMs: Long,
) {
    init {
        require(serverId.isNotBlank())
        require(networkId.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}")))
        require(playerTransferTimeoutMs in 250..60_000)
    }
}
