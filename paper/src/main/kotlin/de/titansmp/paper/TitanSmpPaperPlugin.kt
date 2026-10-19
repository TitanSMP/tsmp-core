package de.titansmp.paper

import de.titansmp.core.lifecycle.TitanSmpRuntime
import de.titansmp.core.enderchest.DefaultEnderChestService
import de.titansmp.core.enderchest.JdbcEnderChestRepository
import de.titansmp.paper.enderchest.EnderChestPaperModule
import de.titansmp.core.databridge.DefaultPlayerDataService
import de.titansmp.core.databridge.JdbcPlayerStateRepository
import de.titansmp.paper.databridge.PlayerDataPaperModule
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
