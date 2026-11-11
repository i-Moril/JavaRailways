package work.art1st.javarailways.bukkit.util

import bukkit.com.rylinaux.plugman.api.PlugManAPI
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginDescriptionFile
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.lifecycle.PlugManXGentleUnload
import java.lang.reflect.Proxy

class PlugManXGentleUnloadTest {
    private fun plugin(version: String = "3.2.1"): Plugin = Proxy.newProxyInstance(
        Plugin::class.java.classLoader, arrayOf(Plugin::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "getDescription" -> PluginDescriptionFile("PlugManX", version, "fixture.Main")
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.get(0)
            "toString" -> "fixture plugin"
            else -> error("Unexpected Plugin access ${method.name}")
        }
    } as Plugin

    @Test
    fun `optional adapter vetoes then approves and removes exact real map entry`() {
        val target = plugin()
        val foreign = plugin()
        val foreignCallback = bukkit.com.rylinaux.plugman.api.GentleUnload { true }
        PlugManAPI.pleaseAddMeToGentleUnload(foreign, foreignCallback)
        var ready = false
        val registration = PlugManXGentleUnload.register(target, plugin()) { ready }
        val callback = PlugManAPI.getGentleUnloads().getValue(target)
        assertFalse(callback.askingForGentleUnload())
        ready = true
        assertTrue(callback.askingForGentleUnload())
        registration.close()
        registration.close()
        assertFalse(PlugManAPI.getGentleUnloads().containsKey(target))
        assertSame(foreignCallback, PlugManAPI.getGentleUnloads()[foreign])
        val replacement = PlugManXGentleUnload.register(target, plugin()) { false }
        registration.close()
        assertTrue(PlugManAPI.getGentleUnloads().containsKey(target), "Old close must preserve replacement")
        replacement.close()
    }

    @Test
    fun `unsupported manager version fails before registering a callback`() {
        val target = plugin()
        assertThrows(IllegalStateException::class.java) {
            PlugManXGentleUnload.register(target, plugin("3.3.0")) { true }
        }
        assertFalse(PlugManAPI.getGentleUnloads().containsKey(target))
    }
}
