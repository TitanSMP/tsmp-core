package de.titansmp.core.lifecycle

import de.titansmp.api.server.NetworkServer
import de.titansmp.core.config.DatabaseConfig
import de.titansmp.core.config.RedisConfig
import de.titansmp.core.config.TitanSmpConfig
import de.titansmp.core.config.TitanSmpConfigLoader
import de.titansmp.core.database.DatabaseMigrator
import de.titansmp.core.database.DatabaseService
import de.titansmp.core.database.HikariDatabaseService
import de.titansmp.core.redis.LettuceRedisService
import de.titansmp.core.redis.RedisService
import de.titansmp.core.server.ConfiguredNetworkServer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

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
    private val logger: Logger = LoggerFactory.getLogger(TitanSmpRuntime::class.java),
) : AutoCloseable {
    @Volatile
    var state: RuntimeState = RuntimeState.STOPPED
        private set

    private var databaseService: DatabaseService? = null
    private var redisService: RedisService? = null
    private var networkServer: NetworkServer? = null

    @Synchronized
    fun start(): NetworkServer {
        check(state == RuntimeState.STOPPED) { "TitanSMP runtime is already starting or ready" }
        state = RuntimeState.STARTING
        logger.info("TitanSMP starting")

        try {
            val configuration = configLoader()
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

            state = RuntimeState.READY
            logger.info("TitanSMP READY")
            return server
        } catch (failure: Throwable) {
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
