package work.art1st.mineRails.bukkit

import org.bukkit.plugin.java.JavaPlugin
import work.art1st.mineRails.bukkit.command.MineRailsCommand
import work.art1st.mineRails.bukkit.config.RailModelConfig
import work.art1st.mineRails.bukkit.config.loadRailModelConfig
import work.art1st.mineRails.bukkit.config.getDefaultRailModelConfig
import work.art1st.mineRails.bukkit.config.model.ModelManager
import work.art1st.mineRails.bukkit.logic.WorldManager
import work.art1st.mineRails.bukkit.render.chunk.ViewDistanceManager
import work.art1st.mineRails.bukkit.tool.RailWand
import work.art1st.mineRails.basic.BasicPlugin
import work.art1st.mineRails.core.api.util.plugin.Plugin
import work.art1st.mineRails.core.api.util.plugin.PluginRegistry
import work.art1st.mineRails.core.di.ensureMineRailsKoinStarted
import java.io.File

class BukkitPlugin : Plugin, JavaPlugin() {
    companion object {
        const val EXTENSION_ID = "BUKKIT"
        lateinit var INSTANCE: BukkitPlugin
    }

    lateinit var viewDistanceManager: ViewDistanceManager
    lateinit var worldManager: WorldManager

    /** 轨道可视化配置，从 rail_model.yml 加载 */
    lateinit var railModelConfig: RailModelConfig

    /** 模型系统管理器 (parts/, cars/, trains/) */
    lateinit var modelManager: ModelManager

    /** RailWand 工具 */
    lateinit var railWand: RailWand

    override fun onEnable() {
        ensureMineRailsKoinStarted()
        BasicPlugin.INSTANCE = BasicPlugin(0)
        PluginRegistry.register(BasicPlugin.INSTANCE)
        PluginRegistry.register(this)
        INSTANCE = this
        viewDistanceManager = ViewDistanceManager()
        worldManager = WorldManager()
        server.pluginManager.registerEvents(worldManager, this)

        // 加载轨道可视化配置
        railModelConfig = loadRailModelConfigFromPlugin()

        // 加载模型系统 (parts/, cars/, trains/)
        modelManager = ModelManager(dataFolder)
        modelManager.loadAll()
        logger.info("Model system loaded: ${modelManager.partRegistry.getAllNames().size} parts, " +
            "${modelManager.carLoader.getAllNames().size} cars, " +
            "${modelManager.trainLoader.getAllNames().size} trains")

        // 初始化 RailWand
        railWand = RailWand(this)
        railWand.init()

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
    }

    private fun loadRailModelConfigFromPlugin(): RailModelConfig {
        val configFile = File(dataFolder, "rail_model.yml")
        return if (configFile.exists()) {
            try {
                configFile.inputStream().use { loadRailModelConfig(it) }
            } catch (e: Exception) {
                logger.warning("Failed to load rail_model.yml: ${e.message}, using default")
                getDefaultRailModelConfig()
            }
        } else {
            try {
                javaClass.getResourceAsStream("/rail_model.yml")?.use { loadRailModelConfig(it) }
                    ?: getDefaultRailModelConfig()
            } catch (e: Exception) {
                logger.warning("Failed to load bundled rail_model.yml: ${e.message}")
                getDefaultRailModelConfig()
            }
        }
    }

    override var identifier: Int = 0
}
