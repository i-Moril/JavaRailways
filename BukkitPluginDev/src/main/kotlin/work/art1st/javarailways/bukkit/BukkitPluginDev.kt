package work.art1st.javarailways.bukkit

import org.bukkit.plugin.java.JavaPlugin
import work.art1st.javarailways.bukkit.tests.ConfigApiTest
import work.art1st.javarailways.bukkit.tests.JrDevCommand
import work.art1st.javarailways.bukkit.tests.PhysTestCommand

class BukkitPluginDev : JavaPlugin() {

    override fun onEnable() {
        registerCommand("phys", "Physics playground: oval track + train test", listOf(), PhysTestCommand(this))

        val configTest = ConfigApiTest()
        ConfigApiTest.INSTANCE = configTest
        configTest.initialize()
        registerCommand("jrdev", "JavaRailways Dev command hub", listOf(), JrDevCommand())

        logger.info("BukkitPluginDev enabled — /jrdev & /phys ready")
    }

    override fun onDisable() {
    }
}
