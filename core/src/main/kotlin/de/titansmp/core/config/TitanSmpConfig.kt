package de.titansmp.core.config

import de.titansmp.api.server.ServerEnvironment
import de.titansmp.api.server.ServerIdentity
import de.titansmp.api.server.ServerType

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
) {
    init {
        require(networkName.isNotBlank()) { "Network name must not be blank" }
        require(networkId.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) { "Network ID must be a lowercase namespace identifier" }
        require(playerTransferTimeoutMs in 250..60_000) { "Player transfer timeout must be between 250 and 60000 milliseconds" }
    }

    override fun toString(): String =
        "TitanSmpConfig(serverIdentity=$serverIdentity, networkName=$networkName, database=<redacted>, redis=<redacted>, playerTransferTimeoutMs=$playerTransferTimeoutMs, chat=$chat, discord=$discord, serverTransfer=$serverTransfer, stats=$stats)"
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
