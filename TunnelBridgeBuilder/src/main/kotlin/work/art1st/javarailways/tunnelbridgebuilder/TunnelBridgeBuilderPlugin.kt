package work.art1st.javarailways.tunnelbridgebuilder

import work.art1st.javarailways.diagnostics.PluginErrorLog
import org.bukkit.plugin.java.JavaPlugin
import work.art1st.javarailways.bukkit.api.operation.PlayerOperationService
import work.art1st.javarailways.bukkit.api.rail.RailTargetService
import work.art1st.javarailways.tunnelbridgebuilder.rail.RailSweepService
import work.art1st.javarailways.tunnelbridgebuilder.wand.BlockWandCommand
import work.art1st.javarailways.tunnelbridgebuilder.wand.BlockWandItem
import work.art1st.javarailways.tunnelbridgebuilder.wand.BlockWandListener
import work.art1st.javarailways.tunnelbridgebuilder.wand.BlockWandMenu

/** Entry point for the separately packaged TunnelBridgeBuilder add-on. */
class TunnelBridgeBuilderPlugin : JavaPlugin() {
    lateinit var blockWandItem: BlockWandItem
        private set
    lateinit var railSweepService: RailSweepService
        private set

    private var errorLog: PluginErrorLog? = null

    override fun onLoad() {
        errorLog = PluginErrorLog(dataFolder.toPath().resolve("logs/errors.log"), logger, "TunnelBridgeBuilder")
    }

    override fun onDisable() {
        errorLog?.close()
        errorLog = null
    }

    override fun onEnable() {
        try {
            enable()
        } catch (failure: Throwable) {
            errorLog?.reportFailure("TunnelBridgeBuilder enable failed", failure)
            throw failure
        }
    }

    private fun enable() {
        val railTargets = requireNotNull(server.servicesManager.load(RailTargetService::class.java)) {
            "JavaRailways did not register RailTargetService"
        }
        val playerOperations = requireNotNull(server.servicesManager.load(PlayerOperationService::class.java)) {
            "JavaRailways did not register PlayerOperationService"
        }
        blockWandItem = BlockWandItem(this)
        railSweepService = RailSweepService(this, railTargets, playerOperations)
        val menu = BlockWandMenu(this, blockWandItem)
        server.pluginManager.registerEvents(menu, this)
        server.pluginManager.registerEvents(BlockWandListener(blockWandItem, menu, railSweepService), this)
        getCommand("tunnelbridgebuilder")?.setExecutor(BlockWandCommand(blockWandItem))
        logger.info("TunnelBridgeBuilder enabled")
    }
}
