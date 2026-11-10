package work.art1st.javarailways.decorations.integration.simpledisplays

import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.ServiceUnregisterEvent
import org.bukkit.plugin.Plugin
import java.lang.reflect.InvocationTargetException
import java.util.logging.Level

/** This class and its connection boundary must remain loadable with no SimpleDisplays API. */
internal class OptionalSimpleDisplays(private val host: Plugin) : Listener, AutoCloseable {
    private var providerOwner: Plugin? = null
    internal var connection: SimpleDisplaysConnection? = null
        private set

    fun start() {
        check(connection == null && providerOwner == null) { "SimpleDisplays discovery already started" }
        val provider = host.server.pluginManager.getPlugin("SimpleDisplays")
        if (provider == null || !provider.isEnabled) {
            host.logger.info("SimpleDisplays is absent or disabled; display integration are unavailable")
            return
        }
        try {
            // A class literal here would risk resolving optional API types during host startup.
            val bindingType = Class.forName("$PACKAGE.SimpleDisplaysBinding", true, javaClass.classLoader)
            val binding = bindingType.getConstructor(Plugin::class.java, Plugin::class.java)
                .newInstance(host, provider) as SimpleDisplaysConnection
            connection = binding
            providerOwner = provider
            try {
                host.server.pluginManager.registerEvents(this, host)
            } catch (failure: RuntimeException) {
                clear()
                throw failure
            }
            host.logger.info("SimpleDisplays API service is available")
        } catch (failure: ReflectiveOperationException) {
            val cause = if (failure is InvocationTargetException) failure.targetException else failure
            if (cause !is Exception && cause !is LinkageError) throw cause
            unavailable(cause)
        } catch (failure: LinkageError) {
            unavailable(failure)
        } catch (failure: RuntimeException) {
            unavailable(failure)
        }
    }

    @EventHandler
    fun onPluginDisable(event: PluginDisableEvent) {
        if (event.plugin === providerOwner) close()
    }

    @EventHandler
    fun onServiceUnregister(event: ServiceUnregisterEvent) {
        val active = connection ?: return
        if (event.provider.service === active.serviceType && event.provider.provider === active.provider) close()
    }

    private fun unavailable(failure: Throwable) {
        clear()
        host.logger.log(Level.WARNING, "SimpleDisplays API service is unavailable or incompatible; display integration are disabled", failure)
    }

    private fun clear() {
        connection?.close()
        connection = null
        providerOwner = null
    }

    override fun close() {
        clear()
        HandlerList.unregisterAll(this)
    }

    private companion object {
        const val PACKAGE = "work.art1st.javarailways.decorations.integration.simpledisplays"
    }
}

/** No optional classes in descriptors: the typed adapter is loaded only after the plugin gate. */
internal interface SimpleDisplaysConnection : AutoCloseable {
    val serviceType: Class<*>
    val provider: Any?
}
