package work.art1st.javarailways.simplesignals

import org.bukkit.plugin.java.JavaPlugin
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitionStore
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsWorldRuntimeManager

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
            services.signalEpochSources()
        )
        val listener = SimpleSignalsWorldLifecycleListener(this, runtimes)
        server.pluginManager.registerEvents(listener, this)
        server.worlds.forEach(listener::installAlreadyLoaded)
        hostServices = services
        worldRuntimes = runtimes
        logger.info("SimpleSignals enabled")
    }

    override fun onDisable() {
        // Host PluginDisableEvent handling removes dead callbacks. Do not create a second unload gate.
        worldRuntimes?.clearLocal()
        worldRuntimes = null
        hostServices = null
    }
}
