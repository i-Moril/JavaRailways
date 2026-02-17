package work.art1st.mineRails.bukkit.logic

import com.destroystokyo.paper.event.server.ServerTickEndEvent
import com.destroystokyo.paper.event.server.ServerTickStartEvent
import org.bukkit.World as BukkitWorld
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import java.util.concurrent.Future
import work.art1st.mineRails.core.physics.World as RailWorld

class WorldManager(
    val railWorlds: MutableMap<BukkitWorld, RailWorld>,
    val maxTickInterval: Double
) : Listener {
    var tickingTasks: List<Future<*>> = listOf()

    @EventHandler
    fun onTickStart(event: ServerTickStartEvent) {
        tickingTasks = railWorlds.map {
            it.value.executeTask()
        }
    }

    @EventHandler
    fun onTickEnd(event: ServerTickEndEvent) {
        try {
            tickingTasks.forEach { it.get() }
        } catch (e: Exception) {

        }
    }
}