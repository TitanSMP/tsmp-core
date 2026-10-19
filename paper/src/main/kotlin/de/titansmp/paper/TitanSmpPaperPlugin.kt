package de.titansmp.paper

import de.titansmp.core.lifecycle.TitanSmpRuntime
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

class TitanSmpPaperPlugin : JavaPlugin() {
    @Volatile private var runtime: TitanSmpRuntime? = null
    private var identityListener: PaperPlayerIdentityListener? = null
    private val runtimeReady = CompletableFuture<PaperPlayerIdentityRuntime>()
    private val lifecycle = Executors.newSingleThreadExecutor { task ->
        Thread(task, "titansmp-lifecycle").apply { isDaemon = true }
    }

    override fun onEnable() {
        identityListener = PaperPlayerIdentityListener(runtimeReady, logger)
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
                    runtimeReady.complete(
                        PaperPlayerIdentityRuntime(startedRuntime.playerService(), startedRuntime.eventBus(), serverIdentity.identity.id),
                    )
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
        lifecycle.execute {
            listener?.awaitTermination()
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
