package de.titansmp.core.lifecycle

import de.titansmp.api.player.PlayerService
import de.titansmp.api.event.EventBus
import de.titansmp.api.server.NetworkServer
import de.titansmp.core.config.DatabaseConfig
import de.titansmp.core.config.ChatConfig
import de.titansmp.core.config.DiscordConfig
import de.titansmp.core.config.RedisConfig
import de.titansmp.core.config.ServerTransferConfig
import de.titansmp.core.config.StatsConfig
import de.titansmp.core.config.FriendsConfig
import de.titansmp.core.config.ClansConfig
import de.titansmp.core.config.TeleportConfig
import de.titansmp.core.config.AuctionHouseConfig
import de.titansmp.core.config.TitanSmpConfig
import de.titansmp.core.config.TitanSmpConfigLoader
import de.titansmp.core.database.DatabaseMigrator
import de.titansmp.core.database.DatabaseService
import de.titansmp.core.database.HikariDatabaseService
import de.titansmp.core.event.LettuceRedisPubSubTransport
import de.titansmp.core.event.RedisEventBus
import de.titansmp.core.redis.LettuceRedisService
import de.titansmp.core.redis.RedisService
import de.titansmp.core.player.DefaultPlayerService
import de.titansmp.core.player.JdbcPlayerRepository
import de.titansmp.core.server.ConfiguredNetworkServer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import javax.sql.DataSource

enum class RuntimeState {
    STOPPED,
    STARTING,
    READY,
}

/** Starts shared infrastructure in order and closes it in reverse order. */
class TitanSmpRuntime(
    private val configLoader: () -> TitanSmpConfig = { TitanSmpConfigLoader().load() },
    private val databaseFactory: (DatabaseConfig) -> DatabaseService = ::HikariDatabaseService,
    private val migrationRunner: (DatabaseService) -> Unit = { DatabaseMigrator().migrate(it) },
    private val redisFactory: (RedisConfig) -> RedisService = ::LettuceRedisService,
    private val eventBusFactory: (RedisService, String) -> EventBus = { redis, networkId ->
        RedisEventBus(networkId, LettuceRedisPubSubTransport(redis.openPubSubConnection()))
    },
    private val logger: Logger = LoggerFactory.getLogger(TitanSmpRuntime::class.java),
) : AutoCloseable {
    @Volatile
    var state: RuntimeState = RuntimeState.STOPPED
        private set

    private var databaseService: DatabaseService? = null
    private var redisService: RedisService? = null
    private var eventBusService: EventBus? = null
    private var networkServer: NetworkServer? = null
    @Volatile var playerTransferTimeoutMs: Long = 5_000
        private set
    @Volatile var chatConfig: ChatConfig = ChatConfig()
        private set
    @Volatile var discordConfig: DiscordConfig = DiscordConfig()
        private set
    @Volatile var serverTransferConfig: ServerTransferConfig = ServerTransferConfig()
        private set
    @Volatile var statsConfig: StatsConfig = StatsConfig()
        private set
    @Volatile var friendsConfig: FriendsConfig = FriendsConfig()
        private set
    @Volatile var clansConfig: ClansConfig = ClansConfig()
        private set
    @Volatile var teleportConfig: TeleportConfig = TeleportConfig()
        private set
    @Volatile var auctionHouseConfig: AuctionHouseConfig = AuctionHouseConfig()
        private set
    @Volatile var networkId: String = "titansmp"
        private set

    /** Stateless facade over the same shared database pool used by every core repository. */
    fun playerService(): PlayerService {
        check(state == RuntimeState.READY) { "TitanSMP runtime must be ready before using player services" }
        return DefaultPlayerService(JdbcPlayerRepository(requireNotNull(databaseService).dataSource))
    }

    fun eventBus(): EventBus {
        check(state == RuntimeState.READY) { "TitanSMP runtime must be ready before using the event bus" }
        return requireNotNull(eventBusService)
    }

    /** Shared database pool for feature-module repositories created at the application boundary. */
    fun databaseDataSource(): DataSource {
        check(state == RuntimeState.READY) { "TitanSMP runtime must be ready before using database services" }
        return requireNotNull(databaseService).dataSource
    }

    /** Shared Redis command connection for transient network coordination such as server queues. */
    fun redisService(): RedisService {
        check(state == RuntimeState.READY) { "TitanSMP runtime must be ready before using Redis services" }
        return requireNotNull(this.redisService)
    }

    @Synchronized
    fun start(): NetworkServer {
        check(state == RuntimeState.STOPPED) { "TitanSMP runtime is already starting or ready" }
        state = RuntimeState.STARTING
        logger.info("TitanSMP starting")

        try {
            val configuration = configLoader()
            playerTransferTimeoutMs = configuration.playerTransferTimeoutMs
            chatConfig = configuration.chat
            discordConfig = configuration.discord
            serverTransferConfig = configuration.serverTransfer
            statsConfig = configuration.stats
            friendsConfig = configuration.friends
            clansConfig = configuration.clans
            teleportConfig = configuration.teleport
            auctionHouseConfig = configuration.auctionHouse
            networkId = configuration.networkId
            val server = ConfiguredNetworkServer(configuration.serverIdentity, configuration.networkName)
            networkServer = server
            logger.info(
                "TitanSMP server identity: id={}, type={}, environment={}, network={}",
                server.identity.id,
                server.identity.type,
                server.identity.environment,
                server.networkName,
            )

            val database = databaseFactory(configuration.database)
            databaseService = database
            database.start()
            logger.info("MariaDB connection established")

            migrationRunner(database)
            logger.info("Database migrations completed")

            val redis = redisFactory(configuration.redis)
            redisService = redis
            redis.start()
            logger.info("Redis connection established")

            eventBusService = eventBusFactory(redis, configuration.networkId)
            logger.info("Redis event bus subscribed for network {}", configuration.networkId)

            state = RuntimeState.READY
            logger.info("TitanSMP READY")
            return server
        } catch (failure: Throwable) {
            logger.error("TitanSMP startup failed ({})", failure.cause?.javaClass?.simpleName ?: failure.javaClass.simpleName)
            closeAfterFailedStart(failure)
            state = RuntimeState.STOPPED
            networkServer = null
            throw RuntimeStartupException("TitanSMP startup failed", failure)
        }
    }

    @Synchronized
    override fun close() {
        if (state == RuntimeState.STOPPED) return

        var closeFailure: Throwable? = null
        closeFailure = closeService(eventBusService, closeFailure)
        eventBusService = null
        closeFailure = closeService(redisService, closeFailure)
        redisService = null
        closeFailure = closeService(databaseService, closeFailure)
        databaseService = null
        networkServer = null
        state = RuntimeState.STOPPED

        if (closeFailure != null) {
            throw RuntimeShutdownException("TitanSMP infrastructure shutdown failed", closeFailure)
        }
        logger.info("TitanSMP STOPPED")
    }

    private fun closeAfterFailedStart(primaryFailure: Throwable) {
        closeService(eventBusService, primaryFailure)
        eventBusService = null
        closeService(redisService, primaryFailure)
        redisService = null
        closeService(databaseService, primaryFailure)
        databaseService = null
    }

    private fun closeService(service: AutoCloseable?, failure: Throwable?): Throwable? {
        if (service == null) return failure
        return try {
            service.close()
            failure
        } catch (closeFailure: Throwable) {
            if (failure == null) closeFailure else {
                failure.addSuppressed(closeFailure)
                failure
            }
        }
    }
}

class RuntimeStartupException(message: String, cause: Throwable) : RuntimeException(message, cause)

class RuntimeShutdownException(message: String, cause: Throwable) : RuntimeException(message, cause)
