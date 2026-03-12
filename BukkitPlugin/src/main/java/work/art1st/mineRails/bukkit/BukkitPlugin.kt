package work.art1st.mineRails.bukkit

import org.bukkit.plugin.java.JavaPlugin
import work.art1st.mineRails.bukkit.logic.WorldManager
import work.art1st.mineRails.bukkit.render.chunk.ViewDistanceManager
import work.art1st.mineRails.core.api.util.plugin.Plugin

object BukkitPlugin : Plugin, JavaPlugin() {
    const val EXTENSION_ID = "BUKKIT"

    lateinit var viewDistanceManager: ViewDistanceManager
    lateinit var worldManager: WorldManager

    override fun onEnable() {
        // Plugin startup logic
        worldManager = WorldManager()
    }

    override fun onDisable() {
        // Plugin shutdown logic
    }

    override var identifier: Int = 0
}
