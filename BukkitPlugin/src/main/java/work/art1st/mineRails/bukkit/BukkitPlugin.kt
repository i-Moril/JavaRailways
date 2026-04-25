package work.art1st.mineRails.bukkit

import org.bukkit.command.CommandExecutor
import org.bukkit.plugin.java.JavaPlugin
import work.art1st.mineRails.bukkit.command.MineRailsCommand
import work.art1st.mineRails.bukkit.config.CarModelConfig
import work.art1st.mineRails.bukkit.config.RailModelConfig
import work.art1st.mineRails.bukkit.config.loadCarModelConfig
import work.art1st.mineRails.bukkit.config.loadRailModelConfig
import work.art1st.mineRails.bukkit.config.getDefaultRailModelConfig
import work.art1st.mineRails.bukkit.logic.WorldManager
import work.art1st.mineRails.bukkit.render.chunk.ViewDistanceManager
import work.art1st.mineRails.basic.BasicPlugin
import work.art1st.mineRails.core.api.util.plugin.Plugin
import work.art1st.mineRails.core.api.util.plugin.PluginRegistry
import work.art1st.mineRails.core.di.ensureMineRailsKoinStarted
import java.io.File

object BukkitPlugin : Plugin, JavaPlugin() {
    const val EXTENSION_ID = "BUKKIT"

    lateinit var viewDistanceManager: ViewDistanceManager
    lateinit var worldManager: WorldManager

    /** 车厢模型配置，从 car_model.yml 加载 */
    lateinit var carModelConfig: CarModelConfig
    /** 轨道可视化配置，从 rail_model.yml 加载 */
    lateinit var railModelConfig: RailModelConfig

    override fun onEnable() {
        ensureMineRailsKoinStarted()
        // BasicPlugin 必须在 BukkitPlugin 之前注册，使 BasicPlugin.identifier = 0
        BasicPlugin.INSTANCE = BasicPlugin(0)
        PluginRegistry.register(BasicPlugin.INSTANCE)
        PluginRegistry.register(BukkitPlugin)
        viewDistanceManager = ViewDistanceManager()
        worldManager = WorldManager()
        server.pluginManager.registerEvents(worldManager, this)

        // 加载车厢模型配置
        carModelConfig = loadCarModelConfigFromPlugin()
        // 加载轨道可视化配置
        railModelConfig = loadRailModelConfigFromPlugin()

        // 注册命令
        val commandExecutor = MineRailsCommand()
        getCommand("minerails")?.setExecutor(commandExecutor)
        getCommand("mr")?.setExecutor(commandExecutor)

        server.worlds.forEach { worldManager.registerRailWorld(it) }
    }

    override fun onDisable() {
        val worlds = worldManager.railWorlds.keys.toList()
        worlds.forEach { worldManager.unregisterRailWorld(it) }
    }

    private fun loadCarModelConfigFromPlugin(): CarModelConfig {
        val configFile = File(dataFolder, "car_model.yml")
        return if (configFile.exists()) {
            try {
                configFile.inputStream().use { loadCarModelConfig(it) }
            } catch (e: Exception) {
                logger.warning("Failed to load car_model.yml: ${e.message}, using default config")
                getDefaultCarModelConfig()
            }
        } else {
            // 从 classpath 加载示例配置
            try {
                javaClass.getResourceAsStream("/car_model.yml")?.use { loadCarModelConfig(it) }
                    ?: getDefaultCarModelConfig()
            } catch (e: Exception) {
                logger.warning("Failed to load bundled car_model.yml: ${e.message}")
                getDefaultCarModelConfig()
            }
        }
    }

    private fun getDefaultCarModelConfig(): CarModelConfig {
        return CarModelConfig(
            car = work.art1st.mineRails.bukkit.config.CarPartsConfig(
                floor = work.art1st.mineRails.bukkit.config.PartConfig(
                    material = "OAK_PLANKS",
                    offset = work.art1st.mineRails.bukkit.config.OffsetConfig(),
                    scale = work.art1st.mineRails.bukkit.config.ScaleConfig(x = 2.0, y = 0.12, z = 0.9)
                ),
                walls = emptyList(),
                roof = null
            ),
            bogie = work.art1st.mineRails.bukkit.config.BogiePartsConfig(
                frame = work.art1st.mineRails.bukkit.config.PartConfig(
                    material = "IRON_BLOCK",
                    offset = work.art1st.mineRails.bukkit.config.OffsetConfig(y = -0.1),
                    scale = work.art1st.mineRails.bukkit.config.ScaleConfig(x = 0.8, y = 0.15, z = 0.7)
                ),
                rail = work.art1st.mineRails.bukkit.config.PartConfig(
                    material = "RAIL",
                    offset = work.art1st.mineRails.bukkit.config.OffsetConfig(y = -0.22),
                    scale = work.art1st.mineRails.bukkit.config.ScaleConfig(x = 1.0, y = 0.05, z = 0.25)
                )
            ),
            seat = work.art1st.mineRails.bukkit.config.SeatConfig(
                offset = work.art1st.mineRails.bukkit.config.OffsetConfig(x = -0.5, y = 0.8),
                interaction = work.art1st.mineRails.bukkit.config.InteractionConfig()
            )
        )
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
