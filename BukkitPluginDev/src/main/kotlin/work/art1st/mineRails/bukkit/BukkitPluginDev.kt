package work.art1st.mineRails.bukkit

import org.bukkit.plugin.java.JavaPlugin
import work.art1st.mineRails.bukkit.tests.PhysTestCommand

class BukkitPluginDev : JavaPlugin() {

    override fun onEnable() {
        getCommand("phys")?.setExecutor(PhysTestCommand())
        logger.info("BukkitPluginDev enabled — /phys ready")
    }

    override fun onDisable() {
    }
}
