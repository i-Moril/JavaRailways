package work.art1st.mineRails.bukkit.logic

import com.destroystokyo.paper.event.server.ServerTickEndEvent
import com.destroystokyo.paper.event.server.ServerTickStartEvent
import org.bukkit.World as BukkitWorld
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import work.art1st.mineRails.bukkit.extension.renderChunkManager
import work.art1st.mineRails.core.physics.CoreWorld as RailWorld

class WorldManager(
    val railWorlds: MutableMap<BukkitWorld, RailWorld>,
    val maxTickInterval: Double
) : Listener {
    @EventHandler
    fun onTickStart(event: ServerTickStartEvent) {
        val currentTimeMs = System.currentTimeMillis()
        railWorlds.forEach { (world, railWorld) ->
            railWorld.tickManager.preTick(currentTimeMs)
        }
    }

    @EventHandler
    fun onTickEnd(event: ServerTickEndEvent) {
        railWorlds.forEach { (world, railWorld) ->
            railWorld.tickManager.postTick()
            railWorld.renderChunkManager.render()
        }
    }
}