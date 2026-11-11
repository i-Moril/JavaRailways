package work.art1st.javarailways.bukkit

import work.art1st.javarailways.bukkit.api.station.StationCallResolverService

import work.art1st.javarailways.bukkit.api.internal.StationCallResolverOwnerLifecycleListener

import work.art1st.javarailways.bukkit.api.internal.DefaultStationCallResolverService

import work.art1st.javarailways.diagnostics.PluginErrorLog

import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import work.art1st.javarailways.bukkit.render.model.SeatModel
import work.art1st.javarailways.bukkit.util.PlatformCleanup
import work.art1st.javarailways.bukkit.util.FoliaRuntimeDiagnostics
import work.art1st.javarailways.bukkit.api.internal.BukkitRailTargetService
import work.art1st.javarailways.bukkit.api.internal.DefaultPlayerOperationService
import work.art1st.javarailways.bukkit.api.internal.OperationOwnerLifecycleListener
import work.art1st.javarailways.bukkit.api.internal.DefaultRailwayObservationService
import work.art1st.javarailways.bukkit.api.internal.ObservationOwnerLifecycleListener
import work.art1st.javarailways.bukkit.api.simulation.SimulationFeatureService
import work.art1st.javarailways.bukkit.api.internal.DefaultSimulationFeatureService
import work.art1st.javarailways.bukkit.api.internal.SimulationFeatureOwnerLifecycleListener
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceService
import work.art1st.javarailways.bukkit.api.internal.DefaultInfrastructureResourceService
import work.art1st.javarailways.bukkit.api.internal.InfrastructureOwnerLifecycleListener
import work.art1st.javarailways.bukkit.api.internal.DefaultSignalEpochSourceService
import work.art1st.javarailways.bukkit.api.internal.registerJavaRailwaysServices
import work.art1st.javarailways.bukkit.api.internal.DefaultModelPresentationService
import work.art1st.javarailways.bukkit.api.internal.ModelOwnerLifecycleListener
import work.art1st.javarailways.bukkit.api.model.ModelPresentationService
import work.art1st.javarailways.bukkit.api.editor.EditorTextService
import work.art1st.javarailways.bukkit.api.internal.DefaultEditorTextService
import work.art1st.javarailways.bukkit.api.internal.EditorTextLifecycleListener
import work.art1st.javarailways.bukkit.render.backend.displayViewRangeForDistance
import work.art1st.javarailways.bukkit.api.observation.RailwayObservationService
import work.art1st.javarailways.bukkit.api.operation.PlayerOperationService
import work.art1st.javarailways.bukkit.api.rail.RailTargetService
import work.art1st.javarailways.bukkit.api.signal.InspectionSourceService
import work.art1st.javarailways.bukkit.api.signal.SignalEpochSourceService
import work.art1st.javarailways.bukkit.api.signal.RoutePlanningRestrictionSourceService
import work.art1st.javarailways.bukkit.behavior.TrainReverseBehaviorListener
import work.art1st.javarailways.bukkit.command.CloudCommandRegistrar
import work.art1st.javarailways.bukkit.config.DisplayRendererBackend
import work.art1st.javarailways.bukkit.config.JavaRailwaysConfig
import work.art1st.javarailways.bukkit.config.PhysicsConfig
import work.art1st.javarailways.bukkit.config.RailModelConfig
import work.art1st.javarailways.bukkit.config.TimetableWebUiMode
import work.art1st.javarailways.bukkit.config.loadRailModelConfig
import work.art1st.javarailways.bukkit.config.model.ModelManager
import work.art1st.javarailways.bukkit.control.TrainControlManager
import work.art1st.javarailways.bukkit.control.TrainHandleInputListener
import work.art1st.javarailways.bukkit.debug.TrainDebugEventListener
import work.art1st.javarailways.bukkit.debug.TrainDebugLog
import work.art1st.javarailways.bukkit.http.TimetableHttpBridge
import work.art1st.javarailways.bukkit.http.TimetableScriptSandbox
import work.art1st.javarailways.bukkit.http.TimetableWebUiSidecar
import work.art1st.javarailways.bukkit.http.WebUiLoginTokens
import work.art1st.javarailways.bukkit.logic.WorldManager
import work.art1st.javarailways.bukkit.timetable.GlobalTimetableReloadCoordinator
import work.art1st.javarailways.bukkit.extension.train.BukkitExtensionDataSerializers
import work.art1st.javarailways.bukkit.render.backend.DisplayRenderBackend
import work.art1st.javarailways.bukkit.render.backend.DisplayRenderBackendFactory
import work.art1st.javarailways.bukkit.render.backend.PacketEntityMotion
import work.art1st.javarailways.bukkit.render.backend.PacketEntityRuntime
import work.art1st.javarailways.bukkit.render.backend.TrainPacketBundleCoordinator
import work.art1st.javarailways.bukkit.render.chunk.ViewDistanceManager
import work.art1st.javarailways.bukkit.tool.RailObjectWand
import work.art1st.javarailways.bukkit.tool.RailWand
import work.art1st.javarailways.bukkit.tool.TurnoutWand
import work.art1st.javarailways.basic.BasicPlugin
import work.art1st.javarailways.simpleatobukkit.SimpleATOBukkitModule
import work.art1st.javarailways.core.api.event.processor.EventRegistration
import work.art1st.javarailways.core.api.event.processor.MainRailEventBus
import work.art1st.javarailways.core.api.util.plugin.Plugin
import work.art1st.javarailways.core.api.util.plugin.PluginRegistry
import work.art1st.javarailways.core.api.threading.host.ThreadGuardHost
import work.art1st.javarailways.core.api.threading.host.ThreadGuardRegistration
import work.art1st.javarailways.bukkit.util.BukkitThreadGuardBridge
import work.art1st.javarailways.bukkit.util.PaperPlatformDispatcher
import work.art1st.javarailways.bukkit.util.PlatformCapabilities
import work.art1st.javarailways.bukkit.util.PlatformDispatcher
import java.io.File
import java.util.logging.Level

class BukkitPlugin : Plugin, JavaPlugin() {
    companion object {
        const val EXTENSION_ID = "BUKKIT"
        private var currentInstance: BukkitPlugin? = null
        var INSTANCE: BukkitPlugin
            get() = checkNotNull(currentInstance) { "JavaRailways runtime is not loaded" }
            set(value) { currentInstance = value }

        fun instanceOrNull(): BukkitPlugin? = currentInstance
    }

    lateinit var viewDistanceManager: ViewDistanceManager
    lateinit var displayRenderBackend: DisplayRenderBackend
    lateinit var worldManager: WorldManager
    lateinit var platformDispatcher: PlatformDispatcher
    lateinit var platformCapabilities: PlatformCapabilities
    lateinit var trainPacketBundles: TrainPacketBundleCoordinator
    lateinit var railTargetService: RailTargetService
        private set
    lateinit var playerOperationService: PlayerOperationService
        private set
    private lateinit var playerOperationServiceImpl: DefaultPlayerOperationService
    lateinit var railwayObservationService: RailwayObservationService
        private set
    private lateinit var railwayObservationServiceImpl: DefaultRailwayObservationService
    lateinit var infrastructureResourceService: InfrastructureResourceService
        private set
    private lateinit var infrastructureResourceServiceImpl: DefaultInfrastructureResourceService
    private lateinit var stationCallResolverServiceImpl: DefaultStationCallResolverService
    lateinit var stationCallResolverService: StationCallResolverService
        private set
    lateinit var simulationFeatureService: SimulationFeatureService
        private set
    private lateinit var simulationFeatureServiceImpl: DefaultSimulationFeatureService
    lateinit var signalEpochSourceService: SignalEpochSourceService
        private set
    lateinit var inspectionSourceService: InspectionSourceService
        private set
    lateinit var routePlanningRestrictionSourceService: RoutePlanningRestrictionSourceService
        private set
    private lateinit var signalEpochSourceServiceImpl: DefaultSignalEpochSourceService
    lateinit var modelPresentationService: ModelPresentationService
        private set
    private lateinit var modelPresentationServiceImpl: DefaultModelPresentationService
    lateinit var editorTextService: EditorTextService
        private set
    private lateinit var editorTextServiceImpl: DefaultEditorTextService
    val worldIssues = work.art1st.javarailways.bukkit.api.diagnostics.WorldIssueService()
    val webUiLoginTokens: WebUiLoginTokens = WebUiLoginTokens()
    lateinit var timetableScriptSandbox: TimetableScriptSandbox
    lateinit var globalTimetableReloadCoordinator: GlobalTimetableReloadCoordinator

    private lateinit var partExtensions: work.art1st.javarailways.bukkit.api.internal.DefaultPartExtensionService

    private var shutdownPrepared = false
    private var shutdownCompleted = false
    private val shutdownFailures = mutableListOf<String>()
    private var basicPluginForRuntime: BasicPlugin? = null
    @Volatile
    internal var isRuntimeActive = false
        private set

    private var packetEntityRuntime: PacketEntityRuntime? = null
    internal val packetDisplayChunks get() = checkNotNull(packetEntityRuntime).displayChunks
    private var timetableHttpBridge: TimetableHttpBridge? = null
    private var timetableWebUiSidecar: TimetableWebUiSidecar? = null
    var packetEntityMotion: PacketEntityMotion? = null
        private set

    /** 全局插件配置，从 config.yml 加载 */
    lateinit var javaRailwaysConfig: JavaRailwaysConfig

    /** 轨道工程与物理配置，从 physics.yml 加载；仅在重启时生效。 */
    lateinit var physicsConfig: PhysicsConfig

    lateinit var cloudCommands: CloudCommandRegistrar
        private set

    /** 轨道可视化配置，从 rail_model.yml 加载 */
    lateinit var railModelConfig: RailModelConfig

    /** 模型系统管理器 (parts/, cars/, trains/) */
    lateinit var modelManager: ModelManager

    /** RailWand 工具 */
    lateinit var railWand: RailWand

    lateinit var turnoutWand: TurnoutWand

    /** 列车控制权与钥匙绑定管理 */
    lateinit var trainControlManager: TrainControlManager

    /** 驾驶座 WASD 手柄输入 */
    lateinit var trainHandleInputListener: TrainHandleInputListener

    /** Core reverse event -> train behavior.js onReverse bridge */
    lateinit var trainReverseBehaviorListener: TrainReverseBehaviorListener

    /** Extra CSV rows for train debug events such as derailment. */
    lateinit var trainDebugEventListener: TrainDebugEventListener

    private var trainReverseEventRegistration: EventRegistration? = null
    private var trainDebugMainEventRegistration: EventRegistration? = null
    private var threadGuardRegistration: ThreadGuardRegistration? = null
    private var errorLog: PluginErrorLog? = null

    /** 运营对象工具（停车点、限速、方向限制、信号等） */
    lateinit var railObjectWand: RailObjectWand

    override fun onLoad() {
        INSTANCE = this
        errorLog = PluginErrorLog(
            dataFolder.toPath().resolve("logs/errors.log"), logger, "Bukkit",
            mapOf(
                java.util.logging.Logger.getLogger("work.art1st.javarailways.bukkit") to "Bukkit",
                java.util.logging.Logger.getLogger("work.art1st.javarailways.core") to "Core",
                java.util.logging.Logger.getLogger("work.art1st.javarailways.basic") to "Basic",
                java.util.logging.Logger.getLogger("work.art1st.javarailways.simpleato") to "SimpleATO",
                java.util.logging.Logger.getLogger("work.art1st.javarailways.simpleatobukkit") to "SimpleATOBukkit"
            )
        )
        try {
            platformCapabilities = PlatformCapabilities.detect(javaClass.classLoader)
            platformDispatcher = PaperPlatformDispatcher(this)
            physicsConfig = PhysicsConfig.load(this)
            javaRailwaysConfig = JavaRailwaysConfig.load(this)
        } catch (failure: Throwable) {
            errorLog?.reportFailure("JavaRailways load failed", failure)
            errorLog?.close()
            errorLog = null
            throw failure
        }
    }

    internal fun requiredSimulationDomainFeatures(world: work.art1st.javarailways.core.physics.CoreWorld): Set<String> {
        val worldId = worldManager.railWorlds.entries.single { it.value === world }.key.uid
        return infrastructureResourceServiceImpl.requiredDomainFeatures(worldId) +
            signalEpochSourceServiceImpl.requiredDomainFeatures(worldId)
    }

    internal fun prepareSimulationDomainFeatures(world: work.art1st.javarailways.core.physics.CoreWorld) =
        simulationFeatureServiceImpl.prepare(worldManager.railWorlds.entries.single { it.value === world }.key.uid)

    override fun onEnable() {
        check(shutdownFailures.isEmpty()) {
            "Previous JavaRailways shutdown was incomplete (${shutdownFailures.joinToString()}); " +
                "restart the server before enabling again"
        }
        INSTANCE = this
        shutdownPrepared = false
        shutdownCompleted = false
        shutdownFailures.clear()
        isRuntimeActive = false
        platformDispatcher = PaperPlatformDispatcher(this)
        server.pluginManager.registerEvents(object : Listener {
            @EventHandler(priority = EventPriority.LOWEST)
            fun beforeDisable(event: PluginDisableEvent) {
                if (event.plugin === this@BukkitPlugin) prepareShutdown()
            }
        }, this)
        try {
            enablePlugin()
            isRuntimeActive = true
            if (platformCapabilities.folia) {
                platformDispatcher.runGlobal { FoliaRuntimeDiagnostics.warnIfSingleWorker(logger) }
            }
        } catch (failure: Throwable) {
            errorLog?.reportFailure("JavaRailways enable failed", failure)
            prepareShutdown()
            finishShutdown()
            throw failure
        }
    }

    private fun enablePlugin() {
        BukkitExtensionDataSerializers.register()
        val packetRuntime = packetEntityRuntime ?: PacketEntityRuntime(this).also {
            packetEntityRuntime = it
        }
        packetRuntime.enable()
        server.pluginManager.registerEvents(packetDisplayChunks, this)
        trainPacketBundles = TrainPacketBundleCoordinator()
        packetEntityMotion = packetRuntime.enablePacketEntityMotion(
            platformDispatcher,
            trainPacketBundles
        )
        logger.info(
            "Packet Seat motion is active: bundled train frames, virtual Seats, and ArmorStand authority carriers"
        )
        displayRenderBackend = DisplayRenderBackendFactory.create(this)
        BasicPlugin.INSTANCE = BasicPlugin(0).also { basicPluginForRuntime = it }
        PluginRegistry.register(BasicPlugin.INSTANCE)
        SimpleATOBukkitModule.install()
        PluginRegistry.register(this)
        INSTANCE = this
        threadGuardRegistration = ThreadGuardHost.registerPlatformBridge(BukkitThreadGuardBridge)
        viewDistanceManager = ViewDistanceManager(javaRailwaysConfig.displayRenderer.playerDistance.maxBlocks)
        playerOperationServiceImpl = DefaultPlayerOperationService(logger)
        playerOperationService = playerOperationServiceImpl
        railwayObservationServiceImpl = DefaultRailwayObservationService(logger)
        railwayObservationService = railwayObservationServiceImpl
        stationCallResolverServiceImpl = DefaultStationCallResolverService()
        stationCallResolverService = stationCallResolverServiceImpl
        simulationFeatureServiceImpl = DefaultSimulationFeatureService()
        simulationFeatureService = simulationFeatureServiceImpl
        signalEpochSourceServiceImpl = DefaultSignalEpochSourceService()
        signalEpochSourceService = signalEpochSourceServiceImpl
        inspectionSourceService = signalEpochSourceServiceImpl
        routePlanningRestrictionSourceService = signalEpochSourceServiceImpl
        infrastructureResourceServiceImpl = DefaultInfrastructureResourceService(signalEpochSourceServiceImpl)
        infrastructureResourceService = infrastructureResourceServiceImpl
        modelPresentationServiceImpl = DefaultModelPresentationService(
            this, platformDispatcher, displayRenderBackend,
            displayViewRangeForDistance(javaRailwaysConfig.displayRenderer.playerDistance.maxBlocks), logger
        )
        modelPresentationService = modelPresentationServiceImpl
        partExtensions = work.art1st.javarailways.bukkit.api.internal.DefaultPartExtensionService(reportDiagnostic = { logger.info(it) }) { subject, failure ->
            logger.log(Level.WARNING, "Part extension validation failed for $subject", failure)
        }
        editorTextServiceImpl = DefaultEditorTextService(this, platformDispatcher, logger)
        editorTextService = editorTextServiceImpl
        worldManager = WorldManager(
            observationService = railwayObservationServiceImpl,
            infrastructureResourceService = infrastructureResourceServiceImpl,
            signalEpochSourceService = signalEpochSourceServiceImpl,
            stationCallResolverService = stationCallResolverServiceImpl,
            modelPresentationService = modelPresentationServiceImpl,
            releaseManualControl = { world ->
                if (::trainControlManager.isInitialized) trainControlManager.releaseWorldOnOwner(world)
            },
            retireManualControl = { world ->
                if (::trainControlManager.isInitialized) trainControlManager.retireWorld(world)
            }
        )
        railTargetService = BukkitRailTargetService(this)
        server.pluginManager.registerEvents(worldManager, this)
        server.servicesManager.register(work.art1st.javarailways.bukkit.api.diagnostics.WorldIssueService::class.java,
            worldIssues, this, org.bukkit.plugin.ServicePriority.Normal)
        server.servicesManager.registerJavaRailwaysServices(
            owner = this,
            railTargets = railTargetService,
            playerOperations = playerOperationService,
            observations = railwayObservationService,
            infrastructure = infrastructureResourceService,
            signalSources = signalEpochSourceService,
            inspectionSources = inspectionSourceService,
            routePlanningSources = routePlanningRestrictionSourceService,
            modelPresentations = modelPresentationService,
            editorTexts = editorTextService,
            simulationFeatures = simulationFeatureService,
            stationCallResolvers = stationCallResolverService
        )
        server.pluginManager.registerEvents(StationCallResolverOwnerLifecycleListener(stationCallResolverServiceImpl), this)
        server.pluginManager.registerEvents(SimulationFeatureOwnerLifecycleListener(simulationFeatureServiceImpl), this)
        server.pluginManager.registerEvents(OperationOwnerLifecycleListener(playerOperationServiceImpl), this)
        server.pluginManager.registerEvents(ObservationOwnerLifecycleListener(railwayObservationServiceImpl), this)
        server.pluginManager.registerEvents(ModelOwnerLifecycleListener(modelPresentationServiceImpl, this), this)
        server.servicesManager.register(
            work.art1st.javarailways.bukkit.api.model.extension.PartExtensionService::class.java,
            partExtensions, this, org.bukkit.plugin.ServicePriority.Normal
        )
        server.pluginManager.registerEvents(
            work.art1st.javarailways.bukkit.api.internal.PartExtensionOwnerLifecycleListener(partExtensions), this
        )
        server.pluginManager.registerEvents(EditorTextLifecycleListener(editorTextServiceImpl, this), this)
        server.pluginManager.registerEvents(
            InfrastructureOwnerLifecycleListener(infrastructureResourceServiceImpl, this),
            this
        )
        trainControlManager = TrainControlManager(this)
        trainControlManager.start()
        trainHandleInputListener = TrainHandleInputListener(trainControlManager)
        trainHandleInputListener.start(this)
        trainReverseBehaviorListener = TrainReverseBehaviorListener()
        trainReverseEventRegistration = MainRailEventBus.register(trainReverseBehaviorListener)
        trainDebugEventListener = TrainDebugEventListener()
        trainDebugMainEventRegistration = MainRailEventBus.register(trainDebugEventListener)

        // 全局配置在 onLoad 读取；packet backend 在 onEnable 使用独立 PacketEvents 的共享 API。
        if (javaRailwaysConfig.trainDebugLog) {
            TrainDebugLog.enabled = true
        }
        logger.info("Train model update policy: ${javaRailwaysConfig.trainModelUpdatePolicy.name.lowercase().replace('_', '-')}")
        val displayBackend = javaRailwaysConfig.displayRenderer.backend
        logger.info("Display renderer backend: ${displayBackend.name.lowercase()}")
        if (displayBackend == DisplayRendererBackend.ENTITYLIB) {
            logger.info(
                "EntityLib renders Display visuals; Seats use packet virtual ArmorStands with native authority carriers"
            )
        }
        logger.info("Physics worker threads per world: ${physicsConfig.workerThreads}")
        val availableProcessors = Runtime.getRuntime().availableProcessors()
        if (physicsConfig.workerThreads >= availableProcessors) {
            logger.warning(
                "physics.yml worker-threads=${physicsConfig.workerThreads} leaves no dedicated CPU for the server main thread " +
                    "(availableProcessors=$availableProcessors); the configured value will still be used"
            )
        }
        if (TrainDebugLog.enabled) {
            logger.warning("Train debug log enabled: ${dataFolder.resolve("debug/train_debug.csv")}")
        }

        // 加载轨道可视化配置
        railModelConfig = loadRailModelConfigFromPlugin()

        // 加载版本化 trains/ 与 rails/ 模型包。
        modelManager = ModelManager(dataFolder, partExtensions)
        val modelLoad = modelManager.publishReload(modelManager.prepareReload())
        modelLoad.failures.forEach { (type, failure) ->
            logger.log(Level.SEVERE, "Train resources unavailable for type $type", failure)
        }
        logger.info(
            "Model packages loaded: ${modelManager.trainPackages.size} train, " +
                "${modelManager.railPackages.size} rail"
        )

        timetableScriptSandbox = TimetableScriptSandbox(this)
        globalTimetableReloadCoordinator = GlobalTimetableReloadCoordinator(this)

        // 初始化 Wand 工具
        railWand = RailWand(this)
        railWand.init()
        railObjectWand = RailObjectWand(this)
        railObjectWand.init()
        turnoutWand = TurnoutWand(this)
        turnoutWand.init()

        // Cloud 通过 Paper Brigadier 注册完整命令树与动态补全。
        cloudCommands = CloudCommandRegistrar(this)

        BukkitThreadGuardBridge.runInSimulationContext {
            server.worlds.forEach { worldManager.registerRailWorld(it) }
        }
        if (javaRailwaysConfig.timetableWebUi.mode != TimetableWebUiMode.DISABLED) {
            val sidecar = TimetableWebUiSidecar(this)
            timetableWebUiSidecar = sidecar
            timetableHttpBridge = TimetableHttpBridge(this).also {
                it.start(sidecar.managedBridgeToken())
            }
            sidecar.start()
        }
        worldManager.start()
    }

    override fun onDisable() {
        prepareShutdown()
        finishShutdown()
    }

    /** PluginDisableEvent precedes enabled=false, while owner cleanup may still be scheduled. */
    private fun prepareShutdown() {
        if (shutdownPrepared) return
        shutdownPrepared = true
        isRuntimeActive = false
        val servicesRetired = runShutdownStep("public services") { server.servicesManager.unregisterAll(this) }
        if (::railTargetService.isInitialized) {
            runShutdownStep("rail target service") { (railTargetService as? BukkitRailTargetService)?.close() }
        }
        if (::playerOperationServiceImpl.isInitialized) {
            runShutdownStep("player operation service") { playerOperationServiceImpl.close() }
        }
        runShutdownStep("Bukkit event registrations") { HandlerList.unregisterAll(this) }
        runShutdownStep("Timetable WebUI sidecar") {
            timetableWebUiSidecar?.stop()
            timetableWebUiSidecar = null
        }
        val httpStopped = runShutdownStep("timetable HTTP bridge") { timetableHttpBridge?.stop() }
        timetableHttpBridge = null
        if (::globalTimetableReloadCoordinator.isInitialized) {
            runShutdownStep("global timetable reload coordinator") { globalTimetableReloadCoordinator.close() }
        }
        if (::timetableScriptSandbox.isInitialized) {
            runShutdownStep("timetable script sandbox") { timetableScriptSandbox.close() }
        }
        val runtimeActionsDrained = runShutdownStep("platform runtime actions") {
            BukkitThreadGuardBridge.runOutsideSimulationContext {
                (platformDispatcher as? PaperPlatformDispatcher)?.stopAndAwait()
            }
        }
        if (::trainHandleInputListener.isInitialized) {
            runShutdownStep("train handle input listener") { trainHandleInputListener.stop() }
        }
        runShutdownStep("platform cleanup completion") {
            BukkitThreadGuardBridge.runOutsideSimulationContext {
                PlatformCleanup.runAndAwait(
                    skipOwnerTasks = platformCapabilities.folia && server.isStopping
                ) {
                    val worldsStopped = runShutdownStep("world manager stop") {
                        if (::worldManager.isInitialized) worldManager.stop()
                    }
                    runShutdownStep("part extension registrations") {
                        if (::partExtensions.isInitialized) partExtensions.close()
                    }
                    if (::editorTextServiceImpl.isInitialized) {
                        runShutdownStep("editor text service") { editorTextServiceImpl.close() }
                    }
                    if (::modelPresentationServiceImpl.isInitialized) {
                        runShutdownStep("model presentation service") { modelPresentationServiceImpl.close() }
                    }
                    if (::railWand.isInitialized) runShutdownStep("rail wand") { railWand.close() }
                    if (::railObjectWand.isInitialized) runShutdownStep("rail object wand") { railObjectWand.close() }
                    BukkitThreadGuardBridge.runInSimulationContext {
                        if (::worldManager.isInitialized) {
                            worldManager.railWorlds.keys.toList().forEach { world ->
                                runShutdownStep("world ${world.uid} save/unregister") {
                                    worldManager.unregisterRailWorld(
                                        world, saveSnapshot = runtimeActionsDrained && servicesRetired &&
                                            httpStopped && worldsStopped
                                    )
                                }
                            }
                        }
                        // unregister restores each world's manual controllers before its final capture.
                        if (::trainControlManager.isInitialized) {
                            runShutdownStep("train control manager") { trainControlManager.stop() }
                        }
                    }
                }
            }
        }
        runShutdownStep("seat listener registry") { SeatModel.SeatInteractionListener.close() }
        if (::worldManager.isInitialized) {
            runShutdownStep("world snapshot writer") { worldManager.finishStopping() }
        }
        if (::simulationFeatureServiceImpl.isInitialized) {
            runShutdownStep("simulation feature service") { simulationFeatureServiceImpl.close() }
        }
        if (::railwayObservationServiceImpl.isInitialized) {
            runShutdownStep("railway observation service") { railwayObservationServiceImpl.close() }
        }
        if (::infrastructureResourceServiceImpl.isInitialized) {
            runShutdownStep("infrastructure resource service") { infrastructureResourceServiceImpl.close() }
        }
        if (::stationCallResolverServiceImpl.isInitialized) {
            runShutdownStep("station call resolver service") { stationCallResolverServiceImpl.close() }
        }
        if (::signalEpochSourceServiceImpl.isInitialized) {
            runShutdownStep("signal epoch source service") { signalEpochSourceServiceImpl.close() }
        }
    }

    private fun finishShutdown() {
        if (shutdownCompleted) return
        shutdownCompleted = true
        if (::playerOperationServiceImpl.isInitialized) {
            runShutdownStep("player operation service") { playerOperationServiceImpl.close() }
        }
        runShutdownStep("train reverse event registration") { trainReverseEventRegistration?.close() }
        trainReverseEventRegistration = null
        runShutdownStep("train debug main event registration") { trainDebugMainEventRegistration?.close() }
        trainDebugMainEventRegistration = null
        runShutdownStep("train debug log") { TrainDebugLog.close() }
        runShutdownStep("packet entity runtime") { packetEntityRuntime?.close() }
        packetEntityMotion = null
        packetEntityRuntime = null
        runShutdownStep("extension plugin registry") {
            basicPluginForRuntime?.let(PluginRegistry::unregister)
            basicPluginForRuntime = null
            PluginRegistry.unregister(this)
        }
        runShutdownStep("WebUI login tokens") { webUiLoginTokens.clear() }
        runShutdownStep("thread guard bridge") { threadGuardRegistration?.close() }
        threadGuardRegistration = null
        if (currentInstance === this) currentInstance = null
        if (shutdownFailures.isNotEmpty()) {
            logger.severe(
                "JavaRailways shutdown was incomplete: ${shutdownFailures.joinToString()}. " +
                    "PlugManX may still unload the plugin and load a new instance despite these cleanup failures. " +
                    "Do not continue hot reloading. Inspect the detailed errors and any process PID above, " +
                    "preserve worlds/ snapshots and .bak files, confirm any leftover WebUI process has exited, " +
                    "then restart the server."
            )
        }
        errorLog?.close()
        errorLog = null
    }

    private inline fun runShutdownStep(component: String, action: () -> Unit): Boolean {
        try {
            action()
            return true
        } catch (failure: Exception) {
            shutdownFailures.add(component)
            logger.log(Level.SEVERE, "Failed to close $component", failure)
            return false
        }
    }

    private fun loadRailModelConfigFromPlugin(): RailModelConfig {
        val configFile = File(dataFolder, "rail_model.yml")
        if (configFile.exists()) {
            return try {
                configFile.inputStream().use { loadRailModelConfig(it) }
            } catch (e: Exception) {
                throw IllegalStateException(
                    "Failed to load rail_model.yml at ${configFile.path}; fix or remove the file instead of silently falling back to defaults",
                    e
                )
            }
        }

        val bundled = javaClass.getResourceAsStream("/rail_model.yml")
            ?: throw IllegalStateException("Bundled rail_model.yml is missing from plugin resources")
        return try {
            bundled.use { loadRailModelConfig(it) }
        } catch (e: Exception) {
            throw IllegalStateException("Failed to load bundled rail_model.yml", e)
        }
    }

    override var identifier: Int = 0
}
