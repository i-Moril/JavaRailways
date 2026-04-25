package work.art1st.mineRails.bukkit.logic

import com.destroystokyo.paper.event.server.ServerTickEndEvent
import com.destroystokyo.paper.event.server.ServerTickStartEvent
import org.bukkit.World as BukkitWorld
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.event.world.WorldUnloadEvent
import work.art1st.mineRails.bukkit.extension.renderChunkManager
import work.art1st.mineRails.bukkit.persistence.WorldPersistence
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.physics.CoreWorld

class WorldManager(
    val railWorlds: MutableMap<BukkitWorld, World> = mutableMapOf()
) : Listener {

    fun registerRailWorld(bukkitWorld: BukkitWorld) {
        if (railWorlds.containsKey(bukkitWorld)) return
        val snapshot = WorldPersistence.tryLoadSnapshot(bukkitWorld)
        railWorlds[bukkitWorld] = RailWorldFactory.createForBukkit(bukkitWorld, snapshot)
    }

    fun unregisterRailWorld(bukkitWorld: BukkitWorld) {
        railWorlds[bukkitWorld]?.let { rw ->
            (rw as? CoreWorld)?.let { WorldPersistence.saveSnapshot(bukkitWorld, it) }
        }
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