package work.art1st.javarailways.bukkit

import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.plugin.ServicePriority
import work.art1st.javarailways.bukkit.api.internal.BukkitRailTargetService
import work.art1st.javarailways.bukkit.api.internal.DefaultPlayerOperationService
import work.art1st.javarailways.bukkit.api.internal.OperationOwnerLifecycleListener
import work.art1st.javarailways.bukkit.api.operation.PlayerOperationService
import work.art1st.javarailways.bukkit.api.rail.RailTargetService
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
import work.art1st.javarailways.bukkit.render.backend.DisplayRenderBackend
import work.art1st.javarailways.bukkit.render.backend.DisplayRenderBackendFactory
import work.art1st.javarailways.bukkit.render.backend.PacketEntityMotion
import work.art1st.javarailways.bukkit.render.backend.PacketEntityRuntime
import work.art1st.javarailways.bukkit.render.backend.TrainPacketBundleCoordinator
import work.art1st.javarailways.bukkit.render.chunk.ViewDistanceManager
import work.art1st.javarailways.bukkit.tool.RailObjectWand
import work.art1st.javarailways.bukkit.tool.RailWand
import work.art1st.javarailways.basic.BasicPlugin
import work.art1st.javarailways.core.api.event.processor.EventRegistration
import work.art1st.javarailways.core.api.event.processor.MainRailEventBus
import work.art1st.javarailways.core.api.util.plugin.Plugin
import work.art1st.javarailways.core.api.util.plugin.PluginRegistry
import work.art1st.javarailways.core.di.ensureJavaRailwaysKoinStarted
import work.art1st.javarailways.core.di.releaseJavaRailwaysKoinRuntime
import work.art1st.javarailways.bukkit.render.model.SeatModel
import work.art1st.javarailways.bukkit.util.PlatformCleanup
import work.art1st.javarailways.bukkit.util.FoliaRuntimeDiagnostics
import work.art1st.javarailways.core.util.ThreadGuard
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
    val webUiLoginTokens: WebUiLoginTokens = WebUiLoginTokens()
    lateinit var timetableScriptSandbox: TimetableScriptSandbox

    private var shutdownPrepared = false
    private var shutdownCompleted = false
    private val shutdownFailures = mutableListOf<String>()
    private var basicPluginForRuntime: BasicPlugin? = null
    @Volatile
    internal var isRuntimeActive = false
        private set

    private var packetEntityRuntime: PacketEntityRuntime? = null
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

    /** 运营对象工具（停车点、限速、方向限制、信号等） */
    lateinit var railObjectWand: RailObjectWand

    override fun onLoad() {
        INSTANCE = this
        platformCapabilities = PlatformCapabilities.detect(javaClass.classLoader)
        platformDispatcher = PaperPlatformDispatcher(this)
        physicsConfig = PhysicsConfig.load(this)
        javaRailwaysConfig = JavaRailwaysConfig.load(this)

    }

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
            enableRuntime()
            isRuntimeActive = true
            if (platformCapabilities.folia) {
                // The scheduler is not necessarily started during initial plugin enable.
                platformDispatcher.runGlobal { FoliaRuntimeDiagnostics.warnIfSingleWorker(logger) }
            }
        } catch (failure: Throwable) {
            // Also runs while enabled when startup fails before Paper invokes onDisable.
            prepareShutdown()
            finishShutdown()
            throw failure
        }
    }

    private fun enableRuntime() {
        val packetRuntime = packetEntityRuntime ?: PacketEntityRuntime(this).also {
            packetEntityRuntime = it
        }
        packetRuntime.enable()
        trainPacketBundles = TrainPacketBundleCoordinator()
        packetEntityMotion = packetRuntime.enablePacketEntityMotion(
            platformDispatcher,
            trainPacketBundles
        )
        logger.info(
            "Packet Seat motion is active: bundled train frames, virtual Seats, and ArmorStand authority carriers"
        )
        displayRenderBackend = DisplayRenderBackendFactory.create(this)
        ensureJavaRailwaysKoinStarted()
        BasicPlugin.INSTANCE = BasicPlugin(0).also { basicPluginForRuntime = it }
        PluginRegistry.register(BasicPlugin.INSTANCE)
        PluginRegistry.register(this)
        INSTANCE = this
        ThreadGuard.installPlatformBridge(BukkitThreadGuardBridge)
        viewDistanceManager = ViewDistanceManager(javaRailwaysConfig.displayRenderer.playerDistance.maxBlocks)
        worldManager = WorldManager()
        server.pluginManager.registerEvents(worldManager, this)
        railTargetService = BukkitRailTargetService(this)
        playerOperationServiceImpl = DefaultPlayerOperationService(logger)
        playerOperationService = playerOperationServiceImpl
        server.servicesManager.register(RailTargetService::class.java, railTargetService, this, ServicePriority.Normal)
        server.servicesManager.register(
            PlayerOperationService::class.java,
            playerOperationService,
            this,
            ServicePriority.Normal
        )
        server.pluginManager.registerEvents(OperationOwnerLifecycleListener(playerOperationServiceImpl), this)
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
        modelManager = ModelManager(dataFolder)
        modelManager.loadAll()
        logger.info(
            "Model packages loaded: ${modelManager.trainPackages.size} train, " +
                "${modelManager.railPackages.size} rail"
        )

        timetableScriptSandbox = TimetableScriptSandbox(this)

        // 初始化 Wand 工具
        railWand = RailWand(this)
        railWand.init()
        railObjectWand = RailObjectWand(this)
        railObjectWand.init()

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

    /** PluginDisableEvent runs before enabled=false; owner tasks can still finish in this window. */
    private fun prepareShutdown() {
        if (shutdownPrepared) return
        shutdownPrepared = true
        isRuntimeActive = false
        // Notify consumers before taking simulation/physics locks. Their service leases must retire
        // before world teardown, including when they observe this PluginDisableEvent after us.
        val servicesRetired = runShutdownStep("public services") { server.servicesManager.unregisterAll(this) }
        if (::railTargetService.isInitialized) {
            runShutdownStep("rail target service") { (railTargetService as? BukkitRailTargetService)?.close() }
        }
        if (::playerOperationServiceImpl.isInitialized) {
            runShutdownStep("player operation service") { playerOperationServiceImpl.close() }
        }
        runShutdownStep("Bukkit event registrations") { HandlerList.unregisterAll(this) }
        runShutdownStep("Timetable WebUI sidecar") { timetableWebUiSidecar?.stop() }
        timetableWebUiSidecar = null
        val httpStopped = runShutdownStep("timetable HTTP bridge") { timetableHttpBridge?.stop() }
        timetableHttpBridge = null
        if (::timetableScriptSandbox.isInitialized) {
            runShutdownStep("timetable script sandbox") { timetableScriptSandbox.close() }
        }
        val runtimeActionsDrained = runShutdownStep("platform runtime actions") {
            BukkitThreadGuardBridge.runOutsideSimulationContext {
                (platformDispatcher as? PaperPlatformDispatcher)?.stopAndAwait()
            }
        }
        if (::worldManager.isInitialized) {
            runShutdownStep("world manager stop") {
                BukkitThreadGuardBridge.runInSimulationContext { worldManager.stop() }
            }
            runShutdownStep("world physics quiescence") {
                BukkitThreadGuardBridge.runInSimulationContext { worldManager.quiescePhysics() }
            }
        }
        if (::trainHandleInputListener.isInitialized) {
            runShutdownStep("train handle input listener") { trainHandleInputListener.stop() }
        }
        runShutdownStep("platform cleanup completion") {
            // Never wait on owner work while retaining the simulation lock.
            BukkitThreadGuardBridge.runOutsideSimulationContext {
                // Native entities are nonpersistent; Folia has already stopped region ticks.
                // Wand mutations persist PDC at their owner-side mutation boundary. The final
                // online-player flush is still required for ordinary hot disable.
                PlatformCleanup.runAndAwait(
                    skipOwnerTasks = platformCapabilities.folia && server.isStopping
                ) {
                    if (::railWand.isInitialized) runShutdownStep("rail wand") { railWand.close() }
                    if (::railObjectWand.isInitialized) runShutdownStep("rail object wand") { railObjectWand.close() }
                    BukkitThreadGuardBridge.runInSimulationContext {
                        if (::trainControlManager.isInitialized) {
                            runShutdownStep("train control manager") { trainControlManager.stop() }
                        }
                        if (::worldManager.isInitialized) {
                            worldManager.railWorlds.keys.toList().forEach { world ->
                                runShutdownStep("world ${world.uid} save/unregister") {
                                    worldManager.unregisterRailWorld(
                                        world, saveSnapshot = runtimeActionsDrained && servicesRetired && httpStopped
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        runShutdownStep("seat listener registry") { SeatModel.SeatInteractionListener.close() }
        if (::worldManager.isInitialized) {
            runShutdownStep("world snapshot writer") { worldManager.finishStopping() }
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
        runShutdownStep("JavaRailways DI scopes") { releaseJavaRailwaysKoinRuntime() }
        runShutdownStep("extension plugin registry") {
            basicPluginForRuntime?.let(PluginRegistry::unregister)
            basicPluginForRuntime = null
            PluginRegistry.unregister(this)
        }
        runShutdownStep("WebUI login tokens") { webUiLoginTokens.clear() }
        runShutdownStep("thread guard bridge") { ThreadGuard.clearPlatformBridge() }
        if (currentInstance === this) currentInstance = null
        if (shutdownFailures.isNotEmpty()) {
            logger.severe(
                "JavaRailways shutdown was incomplete: ${shutdownFailures.joinToString()}. " +
                    "Preserve worlds/ snapshots and .bak files before recovery; hot reload is not verified safe."
            )
        }
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
