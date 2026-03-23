package work.art1st.mineRails.bukkit

import org.bukkit.plugin.java.JavaPlugin
import work.art1st.mineRails.bukkit.logic.WorldManager
import work.art1st.mineRails.bukkit.render.chunk.ViewDistanceManager
import work.art1st.mineRails.core.api.util.plugin.Plugin
import work.art1st.mineRails.core.api.util.plugin.PluginRegistry
import work.art1st.mineRails.core.di.ensureMineRailsKoinStarted

object BukkitPlugin : Plugin, JavaPlugin() {
    const val EXTENSION_ID = "BUKKIT"

    lateinit var viewDistanceManager: ViewDistanceManager
    lateinit var worldManager: WorldManager

    override fun onEnable() {
        ensureMineRailsKoinStarted()
        PluginRegistry.register(BukkitPlugin)
        viewDistanceManager = ViewDistanceManager()
        worldManager = WorldManager()
        server.pluginManager.registerEvents(worldManager, this)
        server.worlds.forEach { worldManager.registerRailWorld(it) }
    }

    override fun onDisable() {
        // Plugin shutdown logic
    }

    override var identifier: Int = 0
}
