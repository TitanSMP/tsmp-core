package de.titansmp.paper

import de.titansmp.core.lifecycle.TitanSmpRuntime
import org.bukkit.plugin.java.JavaPlugin

class TitanSmpPaperPlugin : JavaPlugin() {
    private var runtime: TitanSmpRuntime? = null
    private var identityListener: PaperPlayerIdentityListener? = null

    override fun onEnable() {
        try {
            val startedRuntime = TitanSmpRuntime()
            val server = startedRuntime.start()
            runtime = startedRuntime
            identityListener = PaperPlayerIdentityListener(
                playerService = startedRuntime.playerService(),
                serverId = server.identity.id,
                logger = logger,
            ).also { this@TitanSmpPaperPlugin.server.pluginManager.registerEvents(it, this@TitanSmpPaperPlugin) }
        } catch (failure: Exception) {
            logger.severe("TitanSMP startup failed: ${failure.message}")
            this.server.pluginManager.disablePlugin(this)
        }
    }

    override fun onDisable() {
        identityListener?.close()
        identityListener = null
        try {
            runtime?.close()
        } catch (failure: Exception) {
            logger.severe("TitanSMP shutdown failed: ${failure.message}")
        } finally {
            runtime = null
        }
    }
}
