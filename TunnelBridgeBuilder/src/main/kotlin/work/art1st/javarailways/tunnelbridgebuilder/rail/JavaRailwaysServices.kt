package work.art1st.javarailways.tunnelbridgebuilder.rail

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.event.server.ServiceRegisterEvent
import org.bukkit.event.server.ServiceUnregisterEvent
import org.bukkit.plugin.java.JavaPlugin
import work.art1st.javarailways.bukkit.api.operation.PlayerOperationService
import work.art1st.javarailways.bukkit.api.rail.RailTargetService

/** Resolves services only from this add-on's API class loader; replacing that loader needs an add-on reload. */
internal class JavaRailwaysServices(private val plugin: JavaPlugin) : Listener, AutoCloseable {
    data class Services(val railTargets: RailTargetService, val playerOperations: PlayerOperationService)

    private val generations = ServiceGeneration<Services>()
    private var lease: ServiceGeneration<Services>.Lease? = null
    private var suspended = false
    private var closed = false
    private var revision = 0L

    fun acquire(): ServiceGeneration<Services>.Lease? {
        val observedRevision = synchronized(this) {
            if (closed || suspended || !plugin.isEnabled) return null
            lease?.takeIf { it.isCurrent() }?.let { return it }
            revision
        }
        // ServicesManager can call unregister listeners while holding its own lock. Do not
        // hold our binding lock while looking up registrations (opposite lock order).
        val registry = plugin.server.servicesManager
        val targets = registry.getRegistration(RailTargetService::class.java) ?: return null
        val operations = registry.getRegistration(PlayerOperationService::class.java) ?: return null
        val owner = targets.plugin
        return synchronized(this) {
            if (closed || suspended || revision != observedRevision || !plugin.isEnabled ||
                owner !== operations.plugin || !owner.isEnabled
            ) return null
            lease?.takeIf { it.isCurrent() }?.let { return it }
            generations.bind(Services(targets.provider, operations.provider)) {
                plugin.isEnabled && owner.isEnabled
            }.also { lease = it }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onOwnerDisable(event: PluginDisableEvent) {
        if (event.plugin.name == "JavaRailways") invalidate(suspend = true)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    @Synchronized
    fun onOwnerEnable(event: PluginEnableEvent) {
        if (event.plugin.name == "JavaRailways") suspended = false
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onServiceRegister(event: ServiceRegisterEvent) {
        val type = event.provider.service
        if (type == RailTargetService::class.java || type == PlayerOperationService::class.java) {
            invalidate(suspend = false)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onServiceUnregister(event: ServiceUnregisterEvent) {
        val type = event.provider.service
        if (type == RailTargetService::class.java || type == PlayerOperationService::class.java) {
            invalidate(suspend = false)
        }
    }

    @Synchronized
    private fun invalidate(suspend: Boolean) {
        revision++
        if (suspend) suspended = true
        generations.invalidate()
        lease = null
    }

    @Synchronized
    override fun close() {
        closed = true
        invalidate(suspend = true)
    }
}
