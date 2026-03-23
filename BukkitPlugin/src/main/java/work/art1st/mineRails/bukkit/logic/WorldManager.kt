package work.art1st.mineRails.bukkit.logic

import com.destroystokyo.paper.event.server.ServerTickEndEvent
import com.destroystokyo.paper.event.server.ServerTickStartEvent
import org.bukkit.World as BukkitWorld
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.event.world.WorldUnloadEvent
import work.art1st.mineRails.bukkit.extension.renderChunkManager
import work.art1st.mineRails.core.api.physics.World

class WorldManager(
    val railWorlds: MutableMap<BukkitWorld, World> = mutableMapOf()
) : Listener {

    fun registerRailWorld(bukkitWorld: BukkitWorld) {
        if (railWorlds.containsKey(bukkitWorld)) return
        railWorlds[bukkitWorld] = RailWorldFactory.createForBukkit(bukkitWorld)
    }

    fun unregisterRailWorld(bukkitWorld: BukkitWorld) {
        railWorlds.remove(bukkitWorld)
    }

    @EventHandler
    fun onWorldLoad(event: WorldLoadEvent) {
        registerRailWorld(event.world)
    }

    @EventHandler
    fun onWorldUnload(event: WorldUnloadEvent) {
        unregisterRailWorld(event.world)
    }

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