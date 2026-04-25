package work.art1st.mineRails.bukkit

import org.bukkit.plugin.java.JavaPlugin
import work.art1st.mineRails.bukkit.logic.WorldManager
import work.art1st.mineRails.bukkit.render.chunk.ViewDistanceManager
import work.art1st.mineRails.basic.BasicPlugin
import work.art1st.mineRails.core.api.util.plugin.Plugin
import work.art1st.mineRails.core.api.util.plugin.PluginRegistry
import work.art1st.mineRails.core.di.ensureMineRailsKoinStarted

object BukkitPlugin : Plugin, JavaPlugin() {
    const val EXTENSION_ID = "BUKKIT"

    lateinit var viewDistanceManager: ViewDistanceManager
    lateinit var worldManager: WorldManager

    override fun onEnable() {
        ensureMineRailsKoinStarted()
        // BasicPlugin 必须在 BukkitPlugin 之前注册，使 BasicPlugin.identifier = 0
        BasicPlugin.INSTANCE = BasicPlugin(0)
        PluginRegistry.register(BasicPlugin.INSTANCE)
        PluginRegistry.register(BukkitPlugin)
        viewDistanceManager = ViewDistanceManager()
        worldManager = WorldManager()
        server.pluginManager.registerEvents(worldManager, this)
        server.worlds.forEach { worldManager.registerRailWorld(it) }
    }

    override fun onDisable() {
        val worlds = worldManager.railWorlds.keys.toList()
        worlds.forEach { worldManager.unregisterRailWorld(it) }
    }

    override var identifier: Int = 0
}
