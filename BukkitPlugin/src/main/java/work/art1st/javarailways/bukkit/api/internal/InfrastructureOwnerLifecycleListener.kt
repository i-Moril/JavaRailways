package work.art1st.javarailways.bukkit.api.internal

import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.plugin.Plugin

internal class InfrastructureOwnerLifecycleListener(
    private val service: DefaultInfrastructureLeaseService,
    private val hostPlugin: Plugin
) : Listener {
    @EventHandler
    fun onPluginDisable(event: PluginDisableEvent) {
        // PluginDisableEvent is not cancellable. During host/server shutdown the normal world/service
        // close path owns cleanup; only an independently disabled add-on is a fail-closed event.
        if (event.plugin === hostPlugin || !hostPlugin.isEnabled || Bukkit.isStopping()) return
        service.forcedDisableOwner(event.plugin)
    }
}
