package work.art1st.javarailways.bukkit

import org.bukkit.plugin.java.JavaPlugin
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
        lateinit var INSTANCE: BukkitPlugin

        fun instanceOrNull(): BukkitPlugin? = if (::INSTANCE.isInitialized) INSTANCE else null
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
        packetEntityRuntime = PacketEntityRuntime(this).also { it.load() }
    }

    override fun onEnable() {
        val packetRuntime = checkNotNull(packetEntityRuntime) {
            "Packet entity runtime was not loaded"
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
        BasicPlugin.INSTANCE = BasicPlugin(0)
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

        // 全局配置已在 onLoad 读取，使 packet backend 能在连接注入前完成 load。
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
        server.servicesManager.unregisterAll(this)
        if (::playerOperationServiceImpl.isInitialized) {
            playerOperationServiceImpl.clear()
        }
        runShutdownStep("Timetable WebUI sidecar") { timetableWebUiSidecar?.stop() }
        timetableWebUiSidecar = null
        runShutdownStep("timetable HTTP bridge") { timetableHttpBridge?.stop() }
        timetableHttpBridge = null
        if (::timetableScriptSandbox.isInitialized) {
            runShutdownStep("timetable script sandbox") { timetableScriptSandbox.close() }
        }
        if (::worldManager.isInitialized) {
            runShutdownStep("world manager stop") { worldManager.stop() }
            val worlds = worldManager.railWorlds.keys.toList()
            worlds.forEach { world ->
                runShutdownStep("world ${world.uid} unregister") {
                    BukkitThreadGuardBridge.runInSimulationContext {
                        worldManager.unregisterRailWorld(world)
                    }
                }
            }
            runShutdownStep("world snapshot writer") { worldManager.finishStopping() }
        }
        if (::trainHandleInputListener.isInitialized) {
            runShutdownStep("train handle input listener") { trainHandleInputListener.stop() }
        }
        if (::trainControlManager.isInitialized) {
            runShutdownStep("train control manager") { trainControlManager.stop() }
        }
        runShutdownStep("train reverse event registration") { trainReverseEventRegistration?.close() }
        trainReverseEventRegistration = null
        runShutdownStep("train debug main event registration") { trainDebugMainEventRegistration?.close() }
        trainDebugMainEventRegistration = null
        runShutdownStep("train debug log") { TrainDebugLog.close() }
        runShutdownStep("packet entity runtime") { packetEntityRuntime?.close() }
        packetEntityMotion = null
        packetEntityRuntime = null
        runShutdownStep("thread guard bridge") { ThreadGuard.clearPlatformBridge() }
    }

    private inline fun runShutdownStep(component: String, action: () -> Unit) {
        try {
            action()
        } catch (failure: Exception) {
            logger.log(Level.SEVERE, "Failed to close $component", failure)
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
