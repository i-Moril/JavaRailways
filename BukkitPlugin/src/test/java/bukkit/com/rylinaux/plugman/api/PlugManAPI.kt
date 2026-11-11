package bukkit.com.rylinaux.plugman.api

import org.bukkit.plugin.Plugin

/** Fixture reproduces the verified 3.2.1 API, including its copy-returning getter. */
fun interface GentleUnload { fun askingForGentleUnload(): Boolean }

object PlugManAPI {
    private val gentleUnloads = HashMap<Plugin, GentleUnload>()
    @JvmStatic fun getGentleUnloads(): Map<Plugin, GentleUnload> = HashMap(gentleUnloads)
    @JvmStatic fun pleaseAddMeToGentleUnload(plugin: Plugin, callback: GentleUnload): Boolean {
        if (gentleUnloads.containsKey(plugin)) return false
        gentleUnloads[plugin] = callback
        return true
    }
}
