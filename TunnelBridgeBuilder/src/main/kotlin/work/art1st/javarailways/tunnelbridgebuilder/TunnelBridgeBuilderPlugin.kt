package work.art1st.javarailways.tunnelbridgebuilder

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

    private var serviceBinding: JavaRailwaysServices? = null

    override fun onDisable() {
        serviceBinding?.close()
        serviceBinding = null
    }

    override fun onEnable() {
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
