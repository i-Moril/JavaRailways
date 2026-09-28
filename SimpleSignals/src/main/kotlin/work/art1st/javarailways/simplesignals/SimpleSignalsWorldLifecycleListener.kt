package work.art1st.javarailways.simplesignals

import org.bukkit.World
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.event.world.WorldUnloadEvent
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsWorldInstallStatus
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsWorldRuntimeManager

/** Mirrors host world load boundaries without retaining mutable Bukkit world state. */
internal class SimpleSignalsWorldLifecycleListener(
    private val plugin: SimpleSignalsPlugin,
    private val runtimes: SimpleSignalsWorldRuntimeManager
) : Listener {
    fun installAlreadyLoaded(world: World) {
        install(world)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldLoad(event: WorldLoadEvent) {
        install(event.world)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldUnload(event: WorldUnloadEvent) {
        runtimes.remove(event.world.uid)
    }

    private fun install(world: World) {
        val result = runtimes.install(world.uid)
        when (result.status) {
            SimpleSignalsWorldInstallStatus.INSTALLED ->
                plugin.logger.info("SimpleSignals route planning enabled for world '${world.name}'")
            SimpleSignalsWorldInstallStatus.ALREADY_INSTALLED -> Unit
            SimpleSignalsWorldInstallStatus.UNAVAILABLE ->
                plugin.logger.warning(
                    "SimpleSignals is disabled for world '${world.name}': ${result.detail}"
                )
        }
    }
}
