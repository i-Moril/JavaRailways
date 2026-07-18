package work.art1st.mineRails.bukkit

import org.bukkit.plugin.java.JavaPlugin
import work.art1st.mineRails.bukkit.behavior.TrainReverseBehaviorListener
import work.art1st.mineRails.bukkit.command.MineRailsCommand
import work.art1st.mineRails.bukkit.config.DisplayRendererBackend
import work.art1st.mineRails.bukkit.config.MineRailsConfig
import work.art1st.mineRails.bukkit.config.RailModelConfig
import work.art1st.mineRails.bukkit.config.loadRailModelConfig
import work.art1st.mineRails.bukkit.config.model.ModelManager
import work.art1st.mineRails.bukkit.control.TrainControlManager
import work.art1st.mineRails.bukkit.control.TrainHandleInputListener
import work.art1st.mineRails.bukkit.debug.TrainDebugEventListener
import work.art1st.mineRails.bukkit.debug.TrainDebugLog
import work.art1st.mineRails.bukkit.logic.WorldManager
import work.art1st.mineRails.bukkit.render.backend.DisplayRenderBackend
import work.art1st.mineRails.bukkit.render.backend.DisplayRenderBackendFactory
import work.art1st.mineRails.bukkit.render.backend.PacketEntityRuntime
import work.art1st.mineRails.bukkit.render.chunk.ViewDistanceManager
import work.art1st.mineRails.bukkit.tool.RailObjectWand
import work.art1st.mineRails.bukkit.tool.RailWand
import work.art1st.mineRails.basic.BasicPlugin
import work.art1st.mineRails.core.api.event.processor.EventRegistration
import work.art1st.mineRails.core.api.event.processor.MainRailEventBus
import work.art1st.mineRails.core.api.event.processor.PhysicsRailEventBus
import work.art1st.mineRails.core.api.util.plugin.Plugin
import work.art1st.mineRails.core.api.util.plugin.PluginRegistry
import work.art1st.mineRails.core.di.ensureMineRailsKoinStarted
import work.art1st.mineRails.core.util.ThreadGuard
import work.art1st.mineRails.bukkit.util.BukkitThreadGuardBridge
import work.art1st.mineRails.bukkit.util.PaperPlatformDispatcher
import work.art1st.mineRails.bukkit.util.PlatformDispatcher
import java.io.File

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

    private var packetEntityRuntime: PacketEntityRuntime? = null

    /** 全局插件配置，从 config.yml 加载 */
    lateinit var mineRailsConfig: MineRailsConfig

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
    private var trainDebugEventRegistration: EventRegistration? = null

    /** 运营对象工具（停车点、限速、方向限制、信号等） */
    lateinit var railObjectWand: RailObjectWand

    override fun onLoad() {
        INSTANCE = this
        platformDispatcher = PaperPlatformDispatcher(this)
        mineRailsConfig = MineRailsConfig.load(this)
        if (mineRailsConfig.displayRenderer.backend == DisplayRendererBackend.ENTITYLIB) {
            packetEntityRuntime = PacketEntityRuntime(this).also { it.load() }
        }
    }

    override fun onEnable() {
        packetEntityRuntime?.enable()
        displayRenderBackend = DisplayRenderBackendFactory.create(this)
        ensureMineRailsKoinStarted()
        BasicPlugin.INSTANCE = BasicPlugin(0)
        PluginRegistry.register(BasicPlugin.INSTANCE)
        PluginRegistry.register(this)
        INSTANCE = this
        ThreadGuard.installPlatformBridge(BukkitThreadGuardBridge)
        viewDistanceManager = ViewDistanceManager(mineRailsConfig.displayRenderer.playerDistance.maxBlocks)
        worldManager = WorldManager()
        server.pluginManager.registerEvents(worldManager, this)
        trainControlManager = TrainControlManager(this)
        trainControlManager.start()
        trainHandleInputListener = TrainHandleInputListener(trainControlManager)
        trainHandleInputListener.start(this)
        trainReverseBehaviorListener = TrainReverseBehaviorListener()
        trainReverseEventRegistration = MainRailEventBus.register(trainReverseBehaviorListener)
        trainDebugEventListener = TrainDebugEventListener()
        trainDebugEventRegistration = PhysicsRailEventBus.register(trainDebugEventListener)

        // 全局配置已在 onLoad 读取，使 packet backend 能在连接注入前完成 load。
        if (mineRailsConfig.trainDebugLog) {
            TrainDebugLog.enabled = true
        }
        logger.info("Train model update policy: ${mineRailsConfig.trainModelUpdatePolicy.name.lowercase().replace('_', '-')}")
        logger.info("Display renderer backend: ${mineRailsConfig.displayRenderer.backend.name.lowercase()}")
        if (mineRailsConfig.displayRenderer.backend == DisplayRendererBackend.ENTITYLIB) {
            logger.warning("EntityLib display backend is experimental; interaction, seat and collision entities remain native")
        }
        logger.info("Physics worker threads per world: ${mineRailsConfig.physicsWorkerThreads}")
        val availableProcessors = Runtime.getRuntime().availableProcessors()
        if (mineRailsConfig.physicsWorkerThreads >= availableProcessors) {
            logger.warning(
                "physics-worker-threads=${mineRailsConfig.physicsWorkerThreads} leaves no dedicated CPU for the server main thread " +
                    "(availableProcessors=$availableProcessors); the configured value will still be used"
            )
        }
        if (TrainDebugLog.enabled) {
            logger.warning("Train debug log enabled: ${dataFolder.resolve("debug/train_debug.csv")}")
        }

        // 加载轨道可视化配置
        railModelConfig = loadRailModelConfigFromPlugin()

        // 加载模型系统 (parts/, cars/, trains/)
        modelManager = ModelManager(dataFolder)
        modelManager.loadAll()
        logger.info("Model system loaded: ${modelManager.partRegistry.getAllNames().size} parts, " +
            "${modelManager.carLoader.getAllNames().size} cars, " +
            "${modelManager.trainLoader.getAllNames().size} trains")

        // 初始化 Wand 工具
        railWand = RailWand(this)
        railWand.init()
        railObjectWand = RailObjectWand(this)
        railObjectWand.init()

        // 注册命令
        val mineRailsCmd = MineRailsCommand()
        listOf("minerails", "mr").forEach { name ->
            getCommand(name)?.apply {
                setExecutor(mineRailsCmd)
                setTabCompleter(mineRailsCmd)
            }
        }

        server.worlds.forEach { worldManager.registerRailWorld(it) }
    }

    override fun onDisable() {
        try {
            if (::worldManager.isInitialized) {
                val worlds = worldManager.railWorlds.keys.toList()
                worlds.forEach { worldManager.unregisterRailWorld(it) }
            }
            if (::trainHandleInputListener.isInitialized) runCatching { trainHandleInputListener.stop() }
            if (::trainControlManager.isInitialized) runCatching { trainControlManager.stop() }
            runCatching { trainReverseEventRegistration?.close() }
            trainReverseEventRegistration = null
            runCatching { trainDebugEventRegistration?.close() }
            trainDebugEventRegistration = null
            runCatching { TrainDebugLog.close() }
        } finally {
            runCatching { packetEntityRuntime?.close() }
                .onFailure { logger.severe("Failed to close packet entity runtime: ${it.message}") }
            packetEntityRuntime = null
            ThreadGuard.clearPlatformBridge()
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
