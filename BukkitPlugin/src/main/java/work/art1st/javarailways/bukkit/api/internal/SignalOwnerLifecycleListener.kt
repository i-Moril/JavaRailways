package work.art1st.javarailways.bukkit.api.internal

import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.plugin.Plugin

internal class SignalOwnerLifecycleListener(
    private val service: DefaultSignalEpochSourceService,
    private val hostPlugin: Plugin
) : Listener {
    @EventHandler
    fun onPluginDisable(event: PluginDisableEvent) {
        if (event.plugin === hostPlugin || !hostPlugin.isEnabled || Bukkit.isStopping()) return
        service.forcedDisableOwner(event.plugin)
    }
}
