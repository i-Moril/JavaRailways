package work.art1st.javarailways.bukkit.api.internal

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.plugin.Plugin

/** The host stays enabled while add-on cleanup runs; host close diagnoses an order violation. */
internal class ModelOwnerLifecycleListener(
    private val service: DefaultModelPresentationService,
    private val host: Plugin
) : Listener {
    @EventHandler
    fun onQuit(event: PlayerQuitEvent) { service.closeViewer(event.player) }
    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) { service.closeViewer(event.player) }

    @EventHandler
    fun onPluginDisable(event: PluginDisableEvent) {
        if (event.plugin !== host) service.closeOwner(event.plugin)
    }
}
