package work.art1st.javarailways.decorations.integration.simpledisplays

import org.bukkit.Bukkit
import org.bukkit.Server
import org.bukkit.plugin.PluginManager
import java.lang.reflect.Proxy
import java.util.logging.Logger

/** Add-on-local test binding for real Bukkit lifecycle events; no host test internals. */
internal object BukkitServiceTestSupport {
    fun <T> withServer(block: () -> T): T = synchronized(Bukkit::class.java) {
        val serverField = Bukkit::class.java.getDeclaredField("server").also { it.isAccessible = true }
        val previous = serverField.get(null)
        val manager = Proxy.newProxyInstance(PluginManager::class.java.classLoader, arrayOf(PluginManager::class.java)) {
            _, method, _ -> primitiveDefault(method.returnType)
        } as PluginManager
        val server = Proxy.newProxyInstance(Server::class.java.classLoader, arrayOf(Server::class.java)) { _, method, _ ->
            when (method.name) {
                "getPluginManager" -> manager
                "getLogger" -> Logger.getLogger("service-registration-test")
                "getName" -> "TestServer"
                "getVersion", "getBukkitVersion" -> "test"
                "isPrimaryThread" -> true
                else -> primitiveDefault(method.returnType)
            }
        } as Server
        serverField.set(null, server)
        try { block() } finally { serverField.set(null, previous) }
    }

    private fun primitiveDefault(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }
}
