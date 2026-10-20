package de.titansmp.paper

import de.titansmp.core.lifecycle.TitanSmpRuntime
import de.titansmp.core.enderchest.DefaultEnderChestService
import de.titansmp.core.enderchest.JdbcEnderChestRepository
import de.titansmp.paper.enderchest.EnderChestPaperModule
import de.titansmp.core.databridge.DefaultPlayerDataService
import de.titansmp.core.databridge.JdbcPlayerStateRepository
import de.titansmp.paper.databridge.PlayerDataPaperModule
import de.titansmp.core.moderation.DefaultModerationService
import de.titansmp.core.moderation.JdbcModerationRepository
import de.titansmp.core.moderation.LuckPermsModerationPermissionService
import de.titansmp.core.display.DefaultPlayerDisplayService
import de.titansmp.core.display.LuckPermsDisplayListener
import de.titansmp.core.display.LuckPermsDisplayProvider
import de.titansmp.paper.display.PlayerDisplayPaperModule
import de.titansmp.paper.moderation.ModerationPaperModule
import de.titansmp.chat.DefaultChatService
import de.titansmp.paper.chat.ChatPaperModule
import de.titansmp.core.discord.link.DefaultDiscordLinkService
import de.titansmp.core.discord.link.JdbcDiscordLinkRepository
import de.titansmp.paper.discord.DiscordLinkPaperModule
import de.titansmp.core.economy.DefaultEconomyService
import de.titansmp.core.economy.JdbcEconomyRepository
import de.titansmp.core.economy.LuckPermsEconomyPermissionService
import de.titansmp.paper.economy.EconomyPaperModule
import de.titansmp.paper.servertransfer.ServerQueuePaperModule
import de.titansmp.core.stats.DefaultStatsService
import de.titansmp.core.stats.FractionalAccumulator
import de.titansmp.core.stats.JdbcStatsRepository
import de.titansmp.core.stats.PlaytimeTracker
import de.titansmp.core.stats.StatsAggregator
import de.titansmp.core.stats.StatsTrackingPolicy
import de.titansmp.paper.stats.StatsPaperModule
import de.titansmp.core.friends.DefaultFriendsService
import de.titansmp.core.friends.JdbcFriendsRepository
import de.titansmp.paper.friends.FriendsPaperModule
import de.titansmp.core.clans.*
import de.titansmp.paper.clans.ClansPaperModule
import net.luckperms.api.LuckPerms
import net.luckperms.api.util.Tristate
import org.bukkit.entity.Player
import de.titansmp.api.enderchest.EnderChestPermissionCheck
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

class TitanSmpPaperPlugin : JavaPlugin() {
    @Volatile private var runtime: TitanSmpRuntime? = null
    private var identityListener: PaperPlayerIdentityListener? = null
    @Volatile private var enderChestModule: EnderChestPaperModule? = null
    @Volatile private var playerDataModule: PlayerDataPaperModule? = null
    @Volatile private var moderationModule: ModerationPaperModule? = null
    @Volatile private var chatModule: ChatPaperModule? = null
    @Volatile private var discordModule: DiscordLinkPaperModule? = null
    @Volatile private var displayModule: PlayerDisplayPaperModule? = null
    @Volatile private var displayService: DefaultPlayerDisplayService? = null
    private var displayListener: LuckPermsDisplayListener? = null
    @Volatile private var economyModule: EconomyPaperModule? = null
    @Volatile private var serverQueueModule: ServerQueuePaperModule? = null
    @Volatile private var statsModule: StatsPaperModule? = null
    @Volatile private var friendsModule: FriendsPaperModule? = null
    @Volatile private var clansModule: ClansPaperModule? = null
    private var clanDisplayRefresh: ClanDisplayRefreshListener? = null
    @Volatile private var moderationPersistence: java.util.concurrent.ExecutorService? = null
    @Volatile private var economyPersistence: java.util.concurrent.ExecutorService? = null
    @Volatile private var statsPersistence: java.util.concurrent.ExecutorService? = null
    private val runtimeReady = CompletableFuture<PaperPlayerIdentityRuntime>()
    private val lifecycle = Executors.newSingleThreadExecutor { task ->
        Thread(task, "titansmp-lifecycle").apply { isDaemon = true }
    }

    override fun onEnable() {
        identityListener = PaperPlayerIdentityListener(runtimeReady, logger, { task ->
            Bukkit.getScheduler().runTask(this, Runnable(task))
        })
            .also { server.pluginManager.registerEvents(it, this) }
        lifecycle.execute {
            val startedRuntime = TitanSmpRuntime()
            try {
                val serverIdentity = startedRuntime.start()
                runtime = startedRuntime
                if (!isEnabled) {
                    runtimeReady.completeExceptionally(IllegalStateException("Plugin was disabled during startup"))
                    startedRuntime.close()
                    runtime = null
                } else {
                    Bukkit.getScheduler().runTask(this, Runnable {
                        if (!isEnabled) return@Runnable
                        val luckPerms = Bukkit.getServicesManager().load(LuckPerms::class.java)
                        if (luckPerms == null) {
                            logger.severe("LuckPerms is required to start the TitanSMP EnderChest feature")
                            server.pluginManager.disablePlugin(this)
                            return@Runnable
                        }
                        val statePersistence = Executors.newSingleThreadExecutor { task ->
                            Thread(task, "titansmp-player-state-db").apply { isDaemon = true }
                        }
                        playerDataModule = PlayerDataPaperModule(
                            this,
                            DefaultPlayerDataService(
                                JdbcPlayerStateRepository(startedRuntime.databaseDataSource()),
                                statePersistence,
                            ),
                            serverIdentity.identity.id,
                            startedRuntime.eventBus(),
                            io = statePersistence,
                            transferTimeoutMs = startedRuntime.playerTransferTimeoutMs,
                        ).also { it.register() }
                        identityListener?.onPlayerOnline = { uuid ->
                            Bukkit.getScheduler().runTask(this, Runnable {
                                Bukkit.getPlayer(uuid)?.let { playerDataModule?.loadOnJoin(it) }
                            })
                        }
                        identityListener?.onPlayerOnlineFailure = { uuid, _ ->
                            playerDataModule?.rejectJoin(uuid, "Your player session could not be initialized. Please reconnect.")
                        }
                        identityListener?.beforePlayerOffline = { player ->
                            playerDataModule?.saveOnQuit(player) ?: CompletableFuture.completedFuture(Unit)
                        }
                        val playerAdapter = luckPerms.getPlayerAdapter(Player::class.java)
                        val moderationExecutor = Executors.newFixedThreadPool(2) { task ->
                            Thread(task, "titansmp-moderation-db").apply { isDaemon = true }
                        }
                        moderationPersistence = moderationExecutor
                        val moderationService = DefaultModerationService(
                            JdbcModerationRepository(startedRuntime.databaseDataSource()),
                            LuckPermsModerationPermissionService(luckPerms),
                            startedRuntime.eventBus(),
                            moderationExecutor,
                        )
                        val friendDomainService = if (startedRuntime.friendsConfig.enabled || startedRuntime.clansConfig.enabled) {
                            val friendsRepository = JdbcFriendsRepository(startedRuntime.databaseDataSource())
                            DefaultFriendsService(friendsRepository, startedRuntime.eventBus(), moderationExecutor, serverIdentity.identity.id)
                        } else null
                        moderationModule = ModerationPaperModule(
                            this,
                            moderationService,
                            startedRuntime.playerService(),
                            startedRuntime.eventBus(),
                            serverIdentity.identity.id,
                            moderationExecutor,
                        ).also { it.register() }
                        val economyExecutor = Executors.newFixedThreadPool(2) { task ->
                            Thread(task, "titansmp-economy-db").apply { isDaemon = true }
                        }
                        economyPersistence = economyExecutor
                        economyModule = EconomyPaperModule(
                            this,
                            DefaultEconomyService(
                                JdbcEconomyRepository(startedRuntime.databaseDataSource()),
                                LuckPermsEconomyPermissionService(luckPerms),
                                startedRuntime.playerService(),
                                startedRuntime.eventBus(),
                                economyExecutor,
                            ),
                            startedRuntime.playerService(),
                            startedRuntime.eventBus(),
                            serverIdentity.identity.id,
                            economyExecutor,
                        ).also { it.register() }
                        if (startedRuntime.serverTransferConfig.enabled) {
                            serverQueueModule = ServerQueuePaperModule(
                                this,
                                startedRuntime.eventBus(),
                                startedRuntime.serverTransferConfig,
                                logger,
                            ).also { it.register() }
                        } else {
                            logger.info("Server transfer queues are disabled; queue ActionBars are not registered.")
                        }
                        if (startedRuntime.statsConfig.enabled) {
                            val statsExecutor = Executors.newFixedThreadPool(2) { task ->
                                Thread(task, "titansmp-stats-db").apply { isDaemon = true }
                            }
                            statsPersistence = statsExecutor
                            val policy = StatsTrackingPolicy(startedRuntime.statsConfig, serverIdentity.identity.type.name)
                            val aggregator = StatsAggregator(
                                DefaultStatsService(
                                    JdbcStatsRepository(startedRuntime.databaseDataSource()),
                                    startedRuntime.eventBus(),
                                    statsExecutor,
                                    serverIdentity.identity.id,
                                ),
                                logger,
                            )
                            val damageAccumulator = FractionalAccumulator()
                            statsModule = StatsPaperModule(
                                this,
                                policy,
                                aggregator,
                                PlaytimeTracker(policy.tracks(de.titansmp.api.stats.StatType.PLAYTIME), aggregator),
                                FractionalAccumulator(),
                                damageAccumulator,
                                startedRuntime.eventBus(),
                                serverIdentity.identity.id,
                                startedRuntime.statsConfig.flushIntervalMs,
                            ).also { it.register() }
                        } else {
                            logger.info("TitanSMP statistics are disabled; no statistics listeners are registered.")
                        }
                        val clanService = if (startedRuntime.clansConfig.enabled) {
                            val cfg = startedRuntime.clansConfig
                            DefaultClanService(
                                JdbcClanRepository(startedRuntime.databaseDataSource()),
                                startedRuntime.eventBus(),
                                moderationExecutor,
                                serverIdentity.identity.id,
                                ClanValidationConfig(cfg.pageSize,cfg.invitationExpirationHours,cfg.nameMinLength,cfg.nameMaxLength,cfg.tagMinLength,cfg.tagMaxLength),
                                friendDomainService,
                            )
                        } else null
                        val display = DefaultPlayerDisplayService(
                            LuckPermsDisplayProvider(luckPerms),
                            suffixProviders = listOfNotNull(clanService?.let(::ClanDisplaySuffixProvider)),
                            eventBus = startedRuntime.eventBus(),
                            serverId = serverIdentity.identity.id,
                            logger = logger,
                        )
                        displayService = display
                        clanDisplayRefresh = if (clanService != null) ClanDisplayRefreshListener(startedRuntime.eventBus(),display) else null
                        displayListener = LuckPermsDisplayListener(luckPerms, display, logger)
                        displayModule = PlayerDisplayPaperModule(this, display, startedRuntime.eventBus()).also { it.register() }
                        chatModule = ChatPaperModule(
                            this,
                            DefaultChatService(
                                startedRuntime.eventBus(),
                                // Reuse the central moderation service's source-of-truth mute query.
                                moderationService,
                                LuckPermsModerationPermissionService(luckPerms),
                                display,
                                startedRuntime.chatConfig,
                            ),
                            startedRuntime.eventBus(),
                            serverIdentity.identity.id,
                            moderationExecutor,
                        ).also { it.register() }
                        if (startedRuntime.friendsConfig.enabled && friendDomainService != null) {
                            val repository = JdbcFriendsRepository(startedRuntime.databaseDataSource())
                            friendsModule = FriendsPaperModule(
                                this,
                                friendDomainService,
                                repository,
                                startedRuntime.playerService(),
                                startedRuntime.eventBus(),
                                startedRuntime.friendsConfig.pageSize,
                                moderationExecutor,
                            ).also { it.register() }
                        }
                        if (clanService != null) {
                            val cfg = startedRuntime.clansConfig
                            clansModule = ClansPaperModule(
                                this,
                                clanService,
                                startedRuntime.playerService(),
                                startedRuntime.eventBus(),
                                { player, node -> playerAdapter.getPermissionData(player).checkPermission(node) == Tristate.TRUE },
                                cfg.pageSize,
                                moderationExecutor,
                            ).also { it.register() }
                        }
                        // The Discord bot runtime is a separate process; Paper only shares the linking
                        // service so players can create and revoke their own link codes in game.
                        if (startedRuntime.discordConfig.enabled) {
                            discordModule = DiscordLinkPaperModule(
                                this,
                                DefaultDiscordLinkService(
                                    JdbcDiscordLinkRepository(startedRuntime.databaseDataSource()),
                                    startedRuntime.eventBus(),
                                    serverIdentity.identity.id,
                                    startedRuntime.discordConfig.linking,
                                    moderationExecutor,
                                ),
                                moderationExecutor,
                                startedRuntime.discordConfig.linking.tokenExpirationMinutes,
                            ).also { it.register() }
                        } else {
                            logger.info("Discord integration is disabled; /link and /unlink are not registered.")
                        }
                        val persistence = Executors.newSingleThreadExecutor { task ->
                            Thread(task, "titansmp-ender-chest-db").apply { isDaemon = true }
                        }
                        val enderChestService = DefaultEnderChestService(
                            JdbcEnderChestRepository(startedRuntime.databaseDataSource()),
                            persistence,
                        )
                        enderChestModule = EnderChestPaperModule(
                            this,
                            enderChestService,
                            serverIdentity.identity.id,
                            { player ->
                                EnderChestPermissionCheck { node ->
                                    playerAdapter.getPermissionData(player).checkPermission(node) == Tristate.TRUE
                                }
                            },
                            closePersistence = {
                                persistence.shutdown()
                            },
                        )
                            .also { it.register() }
                        runtimeReady.complete(
                            PaperPlayerIdentityRuntime(
                                startedRuntime.playerService(),
                                startedRuntime.eventBus(),
                                serverIdentity.identity.id,
                            ),
                        )
                    })
                }
            } catch (failure: Throwable) {
                runtimeReady.completeExceptionally(failure)
                logger.severe("TitanSMP startup failed: ${failure.message}")
                Bukkit.getScheduler().runTask(this, Runnable { server.pluginManager.disablePlugin(this) })
            }
        }
    }

    override fun onDisable() {
        runtimeReady.completeExceptionally(IllegalStateException("Plugin was disabled"))
        val listener = identityListener
        identityListener = null
        listener?.stopAccepting()
        val enderChest = enderChestModule
        enderChestModule = null
        val enderChestFlush = enderChest?.flushAndClose()?.toCompletableFuture() ?: CompletableFuture.completedFuture(Unit)
        val playerData = playerDataModule
        playerDataModule = null
        val playerDataFlush = playerData?.flushAndClose()?.toCompletableFuture() ?: CompletableFuture.completedFuture(Unit)
        moderationModule?.close()
        moderationModule = null
        chatModule?.close()
        chatModule = null
        friendsModule?.close()
        friendsModule = null
        clansModule?.close()
        clansModule = null
        discordModule?.close()
        discordModule = null
        economyModule?.close()
        economyModule = null
        serverQueueModule?.close()
        serverQueueModule = null
        statsModule?.flushAndClose()
        statsModule?.close()
        statsModule = null
        displayModule?.close()
        displayModule = null
        displayListener?.close()
        displayListener = null
        clanDisplayRefresh?.close()
        clanDisplayRefresh = null
        displayService?.close()
        displayService = null
        val moderationWorker = moderationPersistence
        moderationPersistence = null
        moderationWorker?.shutdown()
        val economyWorker = economyPersistence
        economyPersistence = null
        economyWorker?.shutdown()
        val statsWorker = statsPersistence
        statsPersistence = null
        statsWorker?.shutdown()
        lifecycle.execute {
            listener?.awaitTermination()
            try {
                enderChestFlush.get(15, java.util.concurrent.TimeUnit.SECONDS)
            } catch (failure: Exception) {
                logger.severe("TitanSMP EnderChest shutdown save failed: ${failure.message}")
            }
            try {
                playerDataFlush.get(15, java.util.concurrent.TimeUnit.SECONDS)
            } catch (failure: Exception) {
                logger.severe("TitanSMP player-state shutdown save failed: ${failure.message}")
            }
            try {
                moderationWorker?.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS)
            } catch (failure: Exception) {
                logger.severe("TitanSMP moderation shutdown failed: ${failure.message}")
            }
            try {
                economyWorker?.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS)
            } catch (failure: Exception) {
                logger.severe("TitanSMP economy shutdown failed: ${failure.message}")
            }
            try {
                statsWorker?.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS)
            } catch (failure: Exception) {
                logger.severe("TitanSMP statistics shutdown failed: ${failure.message}")
            }
            try {
                runtime?.close()
            } catch (failure: Exception) {
                logger.severe("TitanSMP shutdown failed: ${failure.message}")
            } finally {
                runtime = null
                lifecycle.shutdown()
            }
        }
    }
}
