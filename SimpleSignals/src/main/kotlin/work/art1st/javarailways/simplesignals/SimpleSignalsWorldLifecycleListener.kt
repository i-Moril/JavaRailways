package work.art1st.javarailways.simplesignals

import org.bukkit.World
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.event.world.WorldUnloadEvent
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsWorldInstallStatus
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsWorldInstallDefinitionSource
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
            SimpleSignalsWorldInstallStatus.INSTALLED -> {
                val source = when (result.definitionSource) {
                    SimpleSignalsWorldInstallDefinitionSource.MISSING ->
                        "no definitions file; using empty revision 0"
                    SimpleSignalsWorldInstallDefinitionSource.PRIMARY ->
                        "loaded definitions from primary file"
                    SimpleSignalsWorldInstallDefinitionSource.BACKUP ->
                        "recovered definitions from backup file"
                    null -> "definitions source unavailable"
                }
                plugin.logger.info(
                    "SimpleSignals route planning and section occupancy enabled for world " +
                        "'${world.name}': $source"
                )
                result.diagnostics.forEach(plugin.logger::warning)
            }
            SimpleSignalsWorldInstallStatus.ALREADY_INSTALLED -> Unit
            SimpleSignalsWorldInstallStatus.UNAVAILABLE -> {
                plugin.logger.warning(
                    "SimpleSignals is unavailable for world '${world.name}'; " +
                        "new route planning is blocked: ${result.detail}"
                )
                result.diagnostics.forEach(plugin.logger::warning)
            }
        }
    }
}
