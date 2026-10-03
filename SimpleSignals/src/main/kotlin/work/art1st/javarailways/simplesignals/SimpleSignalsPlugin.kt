package work.art1st.javarailways.simplesignals

import org.bukkit.plugin.java.JavaPlugin
import work.art1st.javarailways.simplesignals.block.FIXED_BLOCK_RESOURCE_TYPE
import work.art1st.javarailways.simplesignals.block.FixedBlockRouteResourcePolicy
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitionStore
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsWorldRuntimeManager
import work.art1st.javarailways.simplesignals.command.SimpleSignalsAdminCommand
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsDefinitionDrafts
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.logging.Level

/** Bukkit lifecycle owner for the independently installed SimpleSignals add-on. */
class SimpleSignalsPlugin : JavaPlugin() {
    private var hostServices: SimpleSignalsHostServices? = null
    private var worldRuntimes: SimpleSignalsWorldRuntimeManager? = null
    private var modelCleanup: CompletionStage<Void> = CompletableFuture.completedFuture(null)

    /** Host shutdown must observe this result before disabling JavaRailways. Never block a region thread. */
    fun modelCleanupCompletion(): CompletionStage<Void> = modelCleanup

    override fun onEnable() {
        val services = SimpleSignalsHostServices.load(server.servicesManager)
        val runtimes = SimpleSignalsWorldRuntimeManager(
            this,
            services.infrastructure(),
            services.routePlanningSources(),
            SimpleSignalsWorldDefinitionStore(dataFolder),
            services.signalEpochSources(),
            services.inspectionSources(),
            services.railTargets(),
            services.models(),
            { action -> server.asyncScheduler.runNow(this) { if (isEnabled) action() } },
            { context, failure ->
                // Signal callbacks only enqueue reporting; logger/Bukkit access happens on this worker.
                CompletableFuture.runAsync { logger.log(Level.SEVERE, context, failure) }
            }
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
        server.asyncScheduler.runNow(this) {
            if (isEnabled) runtimes.loadModelPackages(dataFolder.toPath().resolve("models"))
        }
        checkNotNull(getCommand("simplesignals")) { "SimpleSignals command descriptor is missing" }
            .setExecutor(SimpleSignalsAdminCommand(this, runtimes, SimpleSignalsDefinitionDrafts(dataFolder.toPath())))
        logger.info("SimpleSignals enabled")
    }

    override fun onDisable() {
        // Host PluginDisableEvent handling removes dead callbacks. Do not create a second unload gate.
        val services = hostServices
        modelCleanup = worldRuntimes?.clearLocal()
            ?: services?.models()?.closeOwner(this) ?: CompletableFuture.completedFuture(null)
        modelCleanup.whenComplete { _, failure ->
            if (failure == null) logger.info("SimpleSignals model cleanup completed")
            else logger.log(Level.SEVERE, "SimpleSignals model cleanup failed; keep JavaRailways enabled until cleanup is resolved", failure)
        }
        worldRuntimes = null
        hostServices = null
    }
}
