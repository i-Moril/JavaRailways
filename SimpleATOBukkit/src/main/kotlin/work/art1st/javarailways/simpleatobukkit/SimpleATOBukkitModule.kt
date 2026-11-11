package work.art1st.javarailways.simpleatobukkit

import work.art1st.javarailways.core.api.util.plugin.PluginRegistry
import work.art1st.javarailways.simpleato.SimpleATOPlugin

/**
 * Installs SimpleATO into the JavaRailways host lifecycle.
 *
 * This is an embedded module entrypoint, not a Bukkit plugin. [install] is called by the main
 * JavaRailways plugin before any world is created, so Core extension-slot registration remains in
 * the same startup phase as before the module split.
 */
object SimpleATOBukkitModule {
    fun install() {
        val plugin = SimpleATOPlugin(0)
        SimpleATOPlugin.INSTANCE = plugin
        PluginRegistry.register(plugin)
    }
}
