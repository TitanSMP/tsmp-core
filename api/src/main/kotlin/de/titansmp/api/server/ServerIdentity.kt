package de.titansmp.api.server

/** The configured identity of one TitanSMP server instance. */
data class ServerIdentity(
    val id: String,
    val type: ServerType,
    val environment: ServerEnvironment,
) {
    init {
        require(id.isNotBlank()) { "Server ID must not be blank" }
    }
}

enum class ServerType {
    LOBBY,
    SMP,
    BEDWARS,

    /** A standalone network service such as the dedicated Discord bot, not a Minecraft server. */
    DISCORD,
}

enum class ServerEnvironment {
    DEVELOPMENT,
    PRODUCTION,
}

/** Platform-independent information about a TitanSMP network server. */
interface NetworkServer {
    val identity: ServerIdentity
    val networkName: String
}
