package work.art1st.mineRails.bukkit

import org.bukkit.plugin.java.JavaPlugin
import work.art1st.mineRails.bukkit.behavior.TrainReverseBehaviorListener
import work.art1st.mineRails.bukkit.command.MineRailsCommand
import work.art1st.mineRails.bukkit.config.MineRailsConfig
import work.art1st.mineRails.bukkit.config.RailModelConfig
import work.art1st.mineRails.bukkit.config.loadRailModelConfig
import work.art1st.mineRails.bukkit.config.model.ModelManager
import work.art1st.mineRails.bukkit.control.TrainControlManager
import work.art1st.mineRails.bukkit.control.TrainHandleInputListener
import work.art1st.mineRails.bukkit.debug.TrainDebugEventListener
import work.art1st.mineRails.bukkit.debug.TrainDebugLog
import work.art1st.mineRails.bukkit.logic.WorldManager
import work.art1st.mineRails.bukkit.render.chunk.ViewDistanceManager
import work.art1st.mineRails.bukkit.tool.OperationWand
import work.art1st.mineRails.bukkit.tool.RailWand
import work.art1st.mineRails.basic.BasicPlugin
import work.art1st.mineRails.core.api.event.processor.EventManager
import work.art1st.mineRails.core.api.util.plugin.Plugin
import work.art1st.mineRails.core.api.util.plugin.PluginRegistry
import work.art1st.mineRails.core.di.ensureMineRailsKoinStarted
import work.art1st.mineRails.core.util.ThreadGuard
import work.art1st.mineRails.bukkit.util.BukkitThreadGuardBridge
import java.io.File

class BukkitPlugin : Plugin, JavaPlugin() {
    companion object {
        const val EXTENSION_ID = "BUKKIT"
        lateinit var INSTANCE: BukkitPlugin

        fun instanceOrNull(): BukkitPlugin? = if (::INSTANCE.isInitialized) INSTANCE else null
    }

    lateinit var viewDistanceManager: ViewDistanceManager
    lateinit var worldManager: WorldManager

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

    /** 运营对象工具（停车点、限速、方向限制、信号等） */
    lateinit var operationWand: OperationWand

    override fun onEnable() {
        ensureMineRailsKoinStarted()
        BasicPlugin.INSTANCE = BasicPlugin(0)
        PluginRegistry.register(BasicPlugin.INSTANCE)
        PluginRegistry.register(this)
        INSTANCE = this
        ThreadGuard.installPlatformBridge(BukkitThreadGuardBridge)
        viewDistanceManager = ViewDistanceManager()
        worldManager = WorldManager()
        server.pluginManager.registerEvents(worldManager, this)
        trainControlManager = TrainControlManager(this)
        trainControlManager.start()
        trainHandleInputListener = TrainHandleInputListener(trainControlManager)
        trainHandleInputListener.start(this)
        trainReverseBehaviorListener = TrainReverseBehaviorListener()
        EventManager.register(trainReverseBehaviorListener)
        trainDebugEventListener = TrainDebugEventListener()
        EventManager.register(trainDebugEventListener)

        // 加载全局配置
        mineRailsConfig = MineRailsConfig.load(this)
        if (mineRailsConfig.trainDebugLog) {
            TrainDebugLog.enabled = true
        }
        logger.info("Train model update policy: ${mineRailsConfig.trainModelUpdatePolicy.name.lowercase().replace('_', '-')}")
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
        operationWand = OperationWand(this)
        operationWand.init()

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
        val worlds = worldManager.railWorlds.keys.toList()
        worlds.forEach { worldManager.unregisterRailWorld(it) }
        runCatching { trainHandleInputListener.stop() }
        runCatching { trainControlManager.stop() }
        runCatching { EventManager.unregister(trainReverseBehaviorListener) }
        runCatching { EventManager.unregister(trainDebugEventListener) }
        runCatching { TrainDebugLog.close() }
        ThreadGuard.clearPlatformBridge()
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
