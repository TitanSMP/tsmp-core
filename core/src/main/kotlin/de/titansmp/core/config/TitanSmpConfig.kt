package de.titansmp.core.config

import de.titansmp.api.server.ServerEnvironment
import de.titansmp.api.server.ServerIdentity
import de.titansmp.api.server.ServerType
import de.titansmp.api.economy.Money

class TitanSmpConfig(
    val serverIdentity: ServerIdentity,
    val networkName: String,
    val networkId: String = networkName.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-'),
    val database: DatabaseConfig,
    val redis: RedisConfig,
    val playerTransferTimeoutMs: Long = 5_000,
    val chat: ChatConfig = ChatConfig(),
    val discord: DiscordConfig = DiscordConfig(),
    val serverTransfer: ServerTransferConfig = ServerTransferConfig(),
    val stats: StatsConfig = StatsConfig(),
    val friends: FriendsConfig = FriendsConfig(),
    val clans: ClansConfig = ClansConfig(),
    val teleport: TeleportConfig = TeleportConfig(),
    val auctionHouse: AuctionHouseConfig = AuctionHouseConfig(),
    val shop: ShopConfig = ShopConfig(),
) {
    init {
        require(networkName.isNotBlank()) { "Network name must not be blank" }
        require(networkId.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) { "Network ID must be a lowercase namespace identifier" }
        require(playerTransferTimeoutMs in 250..60_000) { "Player transfer timeout must be between 250 and 60000 milliseconds" }
    }

    override fun toString(): String =
        "TitanSmpConfig(serverIdentity=$serverIdentity, networkName=$networkName, database=<redacted>, redis=<redacted>, playerTransferTimeoutMs=$playerTransferTimeoutMs, chat=$chat, discord=$discord, serverTransfer=$serverTransfer, stats=$stats)"
}

data class AuctionHouseConfig(
    val enabled: Boolean = true,
    val expirationHours: Long = 48,
    val itemsPerPage: Int = 45,
    val searchTimeoutSeconds: Long = 30,
    val defaultAuctionLimit: Int = 5,
    val sellFeePercent: Int = 0,
    val aliases: List<String> = listOf("auctionhouse", "auction"),
) {
    init {
        require(expirationHours in 1..720) { "auction-house.expiration-hours must be between 1 and 720" }
        require(itemsPerPage in 1..45) { "auction-house.items-per-page must be between 1 and 45" }
        require(searchTimeoutSeconds in 1..300) { "auction-house.search-timeout-seconds must be between 1 and 300" }
        require(defaultAuctionLimit >= 0) { "auction-house.default-auction-limit must not be negative" }
        require(sellFeePercent in 0..100) { "auction-house.sell-fee-percent must be between 0 and 100" }
        require(aliases.all { it.matches(Regex("[a-z][a-z0-9_-]{0,31}")) } && aliases.distinct().size == aliases.size) {
            "auction-house.aliases must contain unique lowercase command names"
        }
    }
}

data class ShopItemConfig(
    val id: String,
    val material: String,
    val slot: Int,
    val amount: Int,
    val displayName: String,
    val buyPrice: Money?,
    val sellPrice: Money?,
)

data class ShopCategoryConfig(
    val id: String,
    val displayName: String,
    val icon: String,
    val slot: Int,
    val enabled: Boolean,
    val items: List<ShopItemConfig>,
)

data class ShopConfig(val enabled: Boolean = true, val categories: List<ShopCategoryConfig> = emptyList()) {
    init {
        require(categories.map { it.id.lowercase() }.distinct().size == categories.size) { "Shop category IDs must be unique (case-insensitive)" }
        require(categories.all { it.id.matches(Regex("[a-z0-9][a-z0-9_-]{0,63}")) }) { "Shop category IDs must be lowercase identifiers" }
        require(categories.map { it.slot }.distinct().size == categories.size && categories.all { it.slot in 0..44 }) { "Shop category slots must be unique and between 0 and 44" }
        categories.forEach { category ->
            require(category.displayName.isNotBlank() && category.icon.isNotBlank()) { "Shop category '${category.id}' requires a display name and icon" }
            require(category.items.map { it.id.lowercase() }.distinct().size == category.items.size) { "Shop item IDs must be unique in category '${category.id}'" }
            require(category.items.all { it.id.matches(Regex("[a-z0-9][a-z0-9_-]{0,63}")) }) { "Shop item IDs must be lowercase identifiers in category '${category.id}'" }
            require(category.items.all { it.slot in 0..44 }) { "Shop item slots must be between 0 and 44 in category '${category.id}'" }
            category.items.sortedBy { it.id }.chunked(45).forEach { page ->
                require(page.map { it.slot }.distinct().size == page.size) { "Shop item slots must be unique within each page in category '${category.id}'" }
            }
            category.items.forEach { item ->
                require(item.material.isNotBlank()) { "Shop item '${category.id}.${item.id}' requires a material" }
                require(item.amount in 1..64) { "Shop item '${category.id}.${item.id}' amount must be between 1 and 64" }
                require(item.displayName.isNotBlank()) { "Shop item '${category.id}.${item.id}' requires a display name" }
                require(item.buyPrice != null || item.sellPrice != null) { "Shop item '${category.id}.${item.id}' must have a buy-price or sell-price" }
                require(item.buyPrice?.isNonNegative() != false && item.sellPrice?.isNonNegative() != false) { "Shop item '${category.id}.${item.id}' prices cannot be negative" }
                require(item.buyPrice?.minorUnits?.let { it <= Long.MAX_VALUE / 64 } != false && item.sellPrice?.minorUnits?.let { it <= Long.MAX_VALUE / 64 } != false) {
                    "Shop item '${category.id}.${item.id}' price is too large to multiply by a selected quantity"
                }
            }
        }
    }
}

data class FriendsConfig(val enabled: Boolean = true, val pageSize: Int = 10) {
    init { require(pageSize in 1..100) { "friends.page-size must be between 1 and 100" } }
}

data class ClansConfig(
    val enabled: Boolean = true,
    val invitationExpirationHours: Long = 24,
    val pageSize: Int = 10,
    val nameMinLength: Int = 3,
    val nameMaxLength: Int = 24,
    val tagMinLength: Int = 2,
    val tagMaxLength: Int = 8,
) {
    init {
        require(invitationExpirationHours in 1..720) { "clans.invitation-expiration-hours must be between 1 and 720" }
        require(pageSize in 1..100) { "clans.page-size must be between 1 and 100" }
        require(nameMinLength in 1..nameMaxLength && nameMaxLength <= 24) { "Invalid clan name length range" }
        require(tagMinLength in 1..tagMaxLength && tagMaxLength <= 8) { "Invalid clan tag length range" }
    }
}

data class TeleportConfig(
    val enabled: Boolean = true,
    val defaultCooldownMinutes: Int = 30,
    val defaultHomeLimit: Int = 1,
    val requestExpirationSeconds: Long = 60,
    val rtpMaxAttempts: Int = 20,
    val rtpServerTypes: Set<String> = emptySet(),
    val defaultClanHomeLimit: Int = 1,
) {
    init {
        require(defaultCooldownMinutes >= 0) { "teleport.default-cooldown-minutes must not be negative" }
        require(defaultHomeLimit >= 0) { "teleport.default-home-limit must not be negative" }
        require(requestExpirationSeconds in 1..3600) { "teleport.tpa.request-expiration-seconds must be between 1 and 3600" }
        require(rtpMaxAttempts in 1..100) { "teleport.rtp.max-attempts must be between 1 and 100" }
        require(defaultClanHomeLimit >= 0) { "teleport.clan.default-home-limit must not be negative" }
        require(rtpServerTypes.none(String::isBlank))
    }
}

data class ChatConfig(
    val rateLimitEnabled: Boolean = true,
    val messagesPerWindow: Int = 5,
    val windowMs: Long = 3_000,
    val maxMessageLength: Int = 256,
) {
    init {
        require(messagesPerWindow > 0) { "Chat messages-per-window must be positive" }
        require(windowMs > 0) { "Chat rate-limit window must be positive" }
        require(maxMessageLength > 0) { "Chat maximum message length must be positive" }
    }
}

/**
 * Discord integration configuration.
 *
 * Every Discord ID is a configuration value. Discord IDs are never hardcoded in source code; they are
 * read from `config/titansmp.properties` or the matching environment variable. The bot token is read
 * from `discord.token` / `TITANSMP_DISCORD_TOKEN` only and is redacted in [toString].
 *
 * The Discord IDs and the token are required by the dedicated Discord bot runtime but not by Paper, so
 * they are nullable here. [requireBotSettings] enforces them at the bot's application boundary only.
 */
class DiscordConfig(
    val enabled: Boolean = false,
    val token: String? = null,
    val guildId: Long? = null,
    val minecraftChatChannelId: Long? = null,
    val verifiedRoleId: Long? = null,
    val linking: DiscordLinkingConfig = DiscordLinkingConfig(),
) {
    init {
        require(token == null || token.isNotBlank()) { "Discord token must not be blank when set" }
        require(guildId == null || guildId > 0) { "Discord guild ID must be positive" }
        require(minecraftChatChannelId == null || minecraftChatChannelId > 0) { "Discord chat channel ID must be positive" }
        require(verifiedRoleId == null || verifiedRoleId > 0) { "Discord verified role ID must be positive" }
    }

    /**
     * Validates and returns the settings the Discord bot needs. Throws [ConfigurationException] with a
     * clear message when the integration is enabled but incomplete, so the bot fails cleanly instead of
     * connecting with a partial configuration.
     */
    fun requireBotSettings(): DiscordBotSettings {
        if (!enabled) throw ConfigurationException("Discord integration is disabled (discord.enabled=false)")
        val token = token ?: throw ConfigurationException("Missing required configuration 'discord.token' (or 'TITANSMP_DISCORD_TOKEN')")
        val guildId = guildId ?: throw ConfigurationException("Missing required configuration 'discord.guild-id' (or 'TITANSMP_DISCORD_GUILD_ID')")
        val chatChannelId = minecraftChatChannelId
            ?: throw ConfigurationException("Missing required configuration 'discord.minecraft-chat-channel-id' (or 'TITANSMP_DISCORD_CHAT_CHANNEL_ID')")
        val verifiedRoleId = verifiedRoleId
            ?: throw ConfigurationException("Missing required configuration 'discord.verified-role-id' (or 'TITANSMP_DISCORD_VERIFIED_ROLE_ID')")
        return DiscordBotSettings(token, guildId, chatChannelId, verifiedRoleId)
    }

    override fun toString(): String =
        "DiscordConfig(enabled=$enabled, token=<redacted>, guildId=$guildId, minecraftChatChannelId=$minecraftChatChannelId, verifiedRoleId=$verifiedRoleId, linking=$linking)"
}

/** Validated settings for the dedicated Discord bot runtime. Never logged; [token] is a secret. */
data class DiscordBotSettings(
    val token: String,
    val guildId: Long,
    val minecraftChatChannelId: Long,
    val verifiedRoleId: Long,
)

data class DiscordLinkingConfig(
    val tokenExpirationMinutes: Long = 10,
    val maxAttemptsPerWindow: Int = 5,
    val attemptWindowSeconds: Int = 60,
) {
    init {
        require(tokenExpirationMinutes in 1..1_440) { "Discord link token expiration must be between 1 and 1440 minutes" }
        require(maxAttemptsPerWindow > 0) { "Discord link max attempts per window must be positive" }
        require(attemptWindowSeconds > 0) { "Discord link attempt window must be positive" }
    }
}

class DatabaseConfig(
    val host: String,
    val port: Int,
    val database: String,
    val username: String,
    val password: String,
    val maximumPoolSize: Int,
) {
    init {
        require(host.isNotBlank()) { "Database host must not be blank" }
        require(port in 1..65535) { "Database port must be between 1 and 65535" }
        require(database.isNotBlank()) { "Database name must not be blank" }
        require(username.isNotBlank()) { "Database username must not be blank" }
        require(password.isNotBlank()) { "Database password must not be blank" }
        require(maximumPoolSize > 0) { "Database maximum pool size must be positive" }
    }

    val jdbcUrl: String
        get() = "jdbc:mariadb://$host:$port/$database"

    override fun toString(): String =
        "DatabaseConfig(host=$host, port=$port, database=$database, username=$username, password=<redacted>, maximumPoolSize=$maximumPoolSize)"
}

class RedisConfig(
    val host: String,
    val port: Int,
    val password: String?,
) {
    init {
        require(host.isNotBlank()) { "Redis host must not be blank" }
        require(port in 1..65535) { "Redis port must be between 1 and 65535" }
    }

    override fun toString(): String =
        "RedisConfig(host=$host, port=$port, password=<redacted>)"
}

internal fun String.toServerType(key: String): ServerType =
    enumValueOfOrConfigError(key) { value -> ServerType.valueOf(value.uppercase()) }

internal fun String.toServerEnvironment(key: String): ServerEnvironment =
    enumValueOfOrConfigError(key) { value -> ServerEnvironment.valueOf(value.uppercase()) }

private inline fun <T> String.enumValueOfOrConfigError(key: String, parse: (String) -> T): T =
    try {
        parse(trim())
    } catch (exception: IllegalArgumentException) {
        throw ConfigurationException("Invalid value for '$key'", exception)
    }

class ConfigurationException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)
