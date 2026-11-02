package work.art1st.javarailways.bukkit.api.internal

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent

internal class StationCallResolverOwnerLifecycleListener(
    private val service: DefaultStationCallResolverService
) : Listener {
    @EventHandler
    fun onPluginDisable(event: PluginDisableEvent) {
        service.unregisterOwner(event.plugin)
    }
}
