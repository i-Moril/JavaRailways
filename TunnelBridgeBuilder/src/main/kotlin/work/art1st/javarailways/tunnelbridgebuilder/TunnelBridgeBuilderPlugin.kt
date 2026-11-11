package work.art1st.javarailways.tunnelbridgebuilder

import work.art1st.javarailways.diagnostics.PluginErrorLog
import org.bukkit.plugin.java.JavaPlugin
import work.art1st.javarailways.tunnelbridgebuilder.rail.JavaRailwaysServices
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
    private var serviceBinding: JavaRailwaysServices? = null

    override fun onLoad() {
        errorLog = PluginErrorLog(dataFolder.toPath().resolve("logs/errors.log"), logger, "TunnelBridgeBuilder")
    }

    override fun onDisable() {
        try { serviceBinding?.close() }
        finally {
            serviceBinding = null
            errorLog?.close()
            errorLog = null
        }
    }

    override fun onEnable() {
        if (errorLog == null) onLoad()
        try { enable() }
        catch (failure: Throwable) {
            serviceBinding?.close()
            serviceBinding = null
            errorLog?.reportFailure("TunnelBridgeBuilder enable failed", failure)
            throw failure
        }
    }

    private fun enable() {
        val services = JavaRailwaysServices(this)
        serviceBinding = services
        requireNotNull(services.acquire()) { "JavaRailways did not register its required services" }
        server.pluginManager.registerEvents(services, this)
        blockWandItem = BlockWandItem(this)
        railSweepService = RailSweepService(this, services)
        val menu = BlockWandMenu(this, blockWandItem)
        server.pluginManager.registerEvents(menu, this)
        server.pluginManager.registerEvents(BlockWandListener(blockWandItem, menu, railSweepService), this)
        getCommand("tunnelbridgebuilder")?.setExecutor(BlockWandCommand(blockWandItem))
        logger.info("TunnelBridgeBuilder enabled")
    }
}
