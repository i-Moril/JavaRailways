package work.art1st.javarailways.bukkit.lifecycle

import org.bukkit.plugin.Plugin
import java.lang.reflect.Proxy

/** Optional adapter to the verified PlugManX 3.2.1 API; no PlugManX classes are bundled. */
internal class PlugManXGentleUnload private constructor(
    private val plugin: Plugin,
    private val callback: Any,
    private val registrations: MutableMap<Any?, Any?>
) : AutoCloseable {
    override fun close() {
        // 3.2.1 has no unregister API and getGentleUnloads() returns a COPY. Remove only our
        // precise registration, otherwise PlugManX retains the old plugin and its classloader.
        if (registrations[plugin] === callback) registrations.remove(plugin)
    }

    companion object {
        fun register(plugin: Plugin, plugManX: Plugin, request: () -> Boolean): PlugManXGentleUnload {
            check(plugManX.description.version == "3.2.1") {
                "GentleUnload integration requires the verified PlugManX 3.2.1 API"
            }
            val loader = plugManX.javaClass.classLoader
            val api = loader.loadClass("bukkit.com.rylinaux.plugman.api.PlugManAPI")
            val gentle = loader.loadClass("bukkit.com.rylinaux.plugman.api.GentleUnload")
            check(gentle.getMethod("askingForGentleUnload").returnType == Boolean::class.javaPrimitiveType)
            val field = api.getDeclaredField("gentleUnloads").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val registrations = field.get(null) as MutableMap<Any?, Any?>
            val callback = Proxy.newProxyInstance(loader, arrayOf(gentle)) { proxy, method, args ->
                when (method.name) {
                    "askingForGentleUnload" -> request()
                    "equals" -> proxy === args?.get(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "JavaRailways GentleUnload"
                    else -> error("Unexpected GentleUnload method: ${method.name}")
                }
            }
            check(api.getMethod("pleaseAddMeToGentleUnload", Plugin::class.java, gentle)
                .invoke(null, plugin, callback) == true) { "PlugManX refused GentleUnload registration" }
            return PlugManXGentleUnload(plugin, callback, registrations)
        }
    }
}
