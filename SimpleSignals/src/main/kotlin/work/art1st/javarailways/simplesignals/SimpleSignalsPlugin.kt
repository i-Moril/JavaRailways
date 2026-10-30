package work.art1st.javarailways.simplesignals

import org.bukkit.plugin.java.JavaPlugin
import work.art1st.javarailways.diagnostics.PluginErrorLog
import work.art1st.javarailways.simplesignals.block.FIXED_BLOCK_RESOURCE_TYPE
import work.art1st.javarailways.simplesignals.block.FixedBlockRouteResourcePolicy
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitionStore
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsWorldRuntimeManager
import work.art1st.javarailways.simplesignals.command.SimpleSignalsAdminCommand
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsDefinitionDrafts
import work.art1st.javarailways.simplesignals.authoring.RuntimeSectionWandBackend
import work.art1st.javarailways.simplesignals.authoring.SectionWandHandler
import work.art1st.javarailways.simplesignals.authoring.SectionWandOverlay
import work.art1st.javarailways.simplesignals.authoring.RuntimeSectionOverlayBackend
import work.art1st.javarailways.simplesignals.authoring.MeasurementWandHandler
import work.art1st.javarailways.simplesignals.authoring.RailMeasurementBackend
import work.art1st.javarailways.simplesignals.authoring.RoutePreviewWandHandler
import work.art1st.javarailways.simplesignals.authoring.RuntimeRoutePreviewBackend
import work.art1st.javarailways.simplesignals.authoring.CurrentRouteRecording
import work.art1st.javarailways.simplesignals.authoring.RuntimeSignalWandBackend
import work.art1st.javarailways.simplesignals.authoring.SignalWandHandler
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.logging.Level

/** Bukkit lifecycle owner for the independently installed SimpleSignals add-on. */
class SimpleSignalsPlugin : JavaPlugin() {
    private var errorLog: PluginErrorLog? = null
    private var hostServices: SimpleSignalsHostServices? = null
    private var simulationRegistration: AutoCloseable? = null
    private var worldRuntimes: SimpleSignalsWorldRuntimeManager? = null
    private var sectionWands: SectionWandHandler? = null
    private var sectionOverlay: SectionWandOverlay? = null
    private var measurementWand: MeasurementWandHandler? = null
    private var routePreviewWand: RoutePreviewWandHandler? = null
    private var signalWand: SignalWandHandler? = null
    private var modelCleanup: CompletionStage<Void> = CompletableFuture.completedFuture(null)

    /** Host shutdown must observe this result before disabling JavaRailways. Never block a region thread. */
    fun modelCleanupCompletion(): CompletionStage<Void> = modelCleanup

    override fun onLoad() {
        errorLog = PluginErrorLog(dataFolder.toPath().resolve("logs/errors.log"), logger, "SimpleSignals",
            mapOf(java.util.logging.Logger.getLogger("work.art1st.javarailways.simplesignals") to "SimpleSignals"))
    }

    override fun onEnable() {
        try {
            enablePlugin()
        } catch (failure: Throwable) {
            errorLog?.reportFailure("SimpleSignals enable failed", failure)
            throw failure
        }
    }

    private fun enablePlugin() {
        val errors = checkNotNull(errorLog)
        saveDefaultConfig()
        val settings = SimpleSignalsConfig.from(config.get("protection-region-max-distance-meters"))
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
            errors::reportFailure,
            protectionRegionMaxDistanceMeters = settings.protectionRegionMaxDistanceMeters,
            topologyWorker = { action -> server.asyncScheduler.runNow(this) { if (isEnabled) action() } },
            worldIssues = services.worldIssues()
        )
        val fixedBlocks = FixedBlockRouteResourcePolicy(this, runtimes::validateRouteAdmission, runtimes::fixedBlockSnapshot)
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
        simulationRegistration = services.simulationFeatures().register(this, runtimes::prepareSimulation)
        val listener = SimpleSignalsWorldLifecycleListener(this, runtimes)
        server.pluginManager.registerEvents(listener, this)
        server.worlds.forEach(listener::installAlreadyLoaded)
        hostServices = services
        worldRuntimes = runtimes
        server.asyncScheduler.runNow(this) {
            if (isEnabled) {
                runtimes.loadSignalPacks(dataFolder.toPath().resolve("signal-packs"))
                runtimes.loadModelPackages(dataFolder.toPath().resolve("models"))
            }
        }
        val admin = SimpleSignalsAdminCommand(this, runtimes, SimpleSignalsDefinitionDrafts(dataFolder.toPath()))
        val overlay = SectionWandOverlay(this,
            RuntimeSectionOverlayBackend(runtimes::authoringRuntime, services.infrastructure(), services.railTargets(), services.worldIssues()),
            services.editorTexts(), createHighlight = { viewer -> services.models().createEditorHighlight(this, viewer, "section-overlay") },
            showStatus = { sectionWands?.showStatus(it) })
        sectionOverlay = overlay
        server.pluginManager.registerEvents(overlay, this)
        server.onlinePlayers.forEach(overlay::check)
        val wands = SectionWandHandler(this,
            RuntimeSectionWandBackend(runtimes, services.infrastructure(), settings.protectionRegionMaxDistanceMeters, services.railTargets()),
            services.railTargets(), checkOverlay = overlay::check)
        sectionWands = wands
        server.pluginManager.registerEvents(wands, this)
        val previewBackend = RuntimeRoutePreviewBackend(this, runtimes::authoringRuntime, services.infrastructure(),
            services.observations(), services.railTargets())
        val routeAuthoring = CurrentRouteRecording(runtimes, services.infrastructure(), previewBackend)
        val previews = RoutePreviewWandHandler(this, previewBackend, services.railTargets(),
            record = routeAuthoring::record, deletionChoices = routeAuthoring::deletionChoices,
            deleteRoute = routeAuthoring::delete, checkOverlay = overlay::check)
        routePreviewWand = previews
        server.pluginManager.registerEvents(previews, this)
        val signals = SignalWandHandler(this, RuntimeSignalWandBackend(runtimes, services.infrastructure(), targets = services.railTargets()),
            services.railTargets(), checkOverlay = overlay::check)
        signalWand = signals
        server.pluginManager.registerEvents(signals, this)
        val measurements = MeasurementWandHandler(this, RailMeasurementBackend(services.infrastructure(), services.railTargets()),
            services.railTargets())
        measurementWand = measurements
        server.pluginManager.registerEvents(measurements, this)
        checkNotNull(getCommand("simplesignals")) { "SimpleSignals command descriptor is missing" }
            .setExecutor { sender, command, label, args ->
                if (args.take(2) == listOf("wand", "measure"))
                    measurements.onCommand(sender, command, label, args)
                else if (args.take(2) == listOf("wand", "route"))
                    previews.onCommand(sender, command, label, args)
                else if (args.take(2) == listOf("wand", "signal"))
                    signals.onCommand(sender, command, label, args)
                else if (args.firstOrNull() == "wand") wands.onCommand(sender, command, label, args)
                else admin.onCommand(sender, command, label, args)
            }
        logger.info("SimpleSignals enabled")
    }

    override fun onDisable() {
        val closingErrorLog = errorLog
        errorLog = null
        try {
            simulationRegistration?.close()
            simulationRegistration = null
            measurementWand?.close()
            measurementWand = null
            sectionOverlay?.close()
            sectionOverlay = null
            signalWand?.close()
            signalWand = null
            routePreviewWand?.close()
            routePreviewWand = null
            sectionWands?.close()
            sectionWands = null
            // Host PluginDisableEvent handling removes dead callbacks. Do not create a second unload gate.
            val services = hostServices
            val models = worldRuntimes?.clearLocal()
                ?: services?.models()?.closeOwner(this) ?: CompletableFuture.completedFuture(null)
            val texts = services?.editorTexts()?.closeOwner(this) ?: CompletableFuture.completedFuture(null)
            modelCleanup = CompletableFuture.allOf(models.toCompletableFuture(), texts.toCompletableFuture())
        } catch (failure: Throwable) {
            modelCleanup = CompletableFuture.failedFuture(failure)
            throw failure
        } finally {
            // Model/text futures may finish on region owners. Draining the file writer must not.
            modelCleanup = modelCleanup.whenCompleteAsync { _, failure ->
                try {
                    if (failure == null) logger.info("SimpleSignals model/editor cleanup completed")
                    else logger.log(Level.SEVERE, "SimpleSignals model/editor cleanup failed; keep JavaRailways enabled until cleanup is resolved", failure)
                } finally {
                    closingErrorLog?.close()
                }
            }
            worldRuntimes = null
            hostServices = null
        }
    }
}
