package work.art1st.javarailways.bukkit.api.internal

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent

internal class OperationOwnerLifecycleListener(
    private val operations: DefaultPlayerOperationService
) : Listener {
    @EventHandler
    fun onPluginDisable(event: PluginDisableEvent) {
        operations.unregisterOwner(event.plugin)
    }
}
