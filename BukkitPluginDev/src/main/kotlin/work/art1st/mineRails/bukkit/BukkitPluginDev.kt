package work.art1st.mineRails.bukkit

import org.bukkit.plugin.java.JavaPlugin
import work.art1st.mineRails.bukkit.tests.ConfigApiTest
import work.art1st.mineRails.bukkit.tests.MrDevCommand
import work.art1st.mineRails.bukkit.tests.PhysTestCommand

class BukkitPluginDev : JavaPlugin() {

    override fun onEnable() {
        registerCommand("phys", "Physics playground: oval track + train test", listOf(), PhysTestCommand())

        val configTest = ConfigApiTest()
        ConfigApiTest.INSTANCE = configTest
        configTest.initialize()
        registerCommand("mrdev", "MineRails Dev command hub", listOf(), MrDevCommand())

        logger.info("BukkitPluginDev enabled — /mrdev & /phys ready")
    }

    override fun onDisable() {
    }
}
