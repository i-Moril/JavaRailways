package work.art1st.javarailways.simplesignals

import org.bukkit.plugin.java.JavaPlugin
import work.art1st.javarailways.simplesignals.block.FIXED_BLOCK_RESOURCE_TYPE
import work.art1st.javarailways.simplesignals.block.FixedBlockRouteResourcePolicy
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitionStore
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsWorldRuntimeManager
import work.art1st.javarailways.simplesignals.command.SimpleSignalsAdminCommand
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsDefinitionDrafts

/** Bukkit lifecycle owner for the independently installed SimpleSignals add-on. */
class SimpleSignalsPlugin : JavaPlugin() {
    private var hostServices: SimpleSignalsHostServices? = null
    private var worldRuntimes: SimpleSignalsWorldRuntimeManager? = null

    override fun onEnable() {
        val services = SimpleSignalsHostServices.load(server.servicesManager)
        val runtimes = SimpleSignalsWorldRuntimeManager(
            this,
            services.infrastructure(),
            services.routePlanningSources(),
            SimpleSignalsWorldDefinitionStore(dataFolder),
            services.signalEpochSources(),
            services.inspectionSources()
        )
        val fixedBlocks = FixedBlockRouteResourcePolicy(this, runtimes::fixedBlockSnapshot)
        val providerRegistration = services.infrastructure().registerResourceProvider(
            this,
            FIXED_BLOCK_RESOURCE_TYPE,
            fixedBlocks,
            emptyList()
        )
        try {
            services.infrastructure().registerRouteResourceContributor(this, fixedBlocks)
        } catch (failure: Exception) {
            providerRegistration.close()
            throw failure
        }
        val listener = SimpleSignalsWorldLifecycleListener(this, runtimes)
        server.pluginManager.registerEvents(listener, this)
        server.worlds.forEach(listener::installAlreadyLoaded)
        hostServices = services
        worldRuntimes = runtimes
        checkNotNull(getCommand("simplesignals")) { "SimpleSignals command descriptor is missing" }
            .setExecutor(SimpleSignalsAdminCommand(this, runtimes, SimpleSignalsDefinitionDrafts(dataFolder.toPath())))
        logger.info("SimpleSignals enabled")
    }

    override fun onDisable() {
        // Host PluginDisableEvent handling removes dead callbacks. Do not create a second unload gate.
        worldRuntimes?.clearLocal()
        worldRuntimes = null
        hostServices = null
    }
}
