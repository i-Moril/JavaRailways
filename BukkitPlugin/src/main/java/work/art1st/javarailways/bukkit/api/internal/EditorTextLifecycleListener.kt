package work.art1st.javarailways.bukkit.api.internal

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.plugin.Plugin

/** The JavaRailways scheduler remains the cleanup owner after an add-on stops. */
internal class EditorTextLifecycleListener(
    private val service: DefaultEditorTextService,
    private val host: Plugin
) : Listener {
    @EventHandler
    fun onQuit(event: PlayerQuitEvent) { service.closeViewer(event.player) }

    @EventHandler
    fun onChangedWorld(event: PlayerChangedWorldEvent) { service.closeViewer(event.player) }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onWorldUnload(event: WorldUnloadEvent) { service.removeWorld(event.world) }

    @EventHandler
    fun onPluginDisable(event: PluginDisableEvent) {
        if (event.plugin !== host) service.closeOwner(event.plugin)
    }
}
