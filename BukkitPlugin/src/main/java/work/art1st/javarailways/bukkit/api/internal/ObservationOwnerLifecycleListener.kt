package work.art1st.javarailways.bukkit.api.internal

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent

internal class ObservationOwnerLifecycleListener(
    private val observations: DefaultRailwayObservationService
) : Listener {
    @EventHandler
    fun onPluginDisable(event: PluginDisableEvent) {
        observations.unregisterOwner(event.plugin)
    }
}
