package work.art1st.mineRails.bukkit.logic

import com.destroystokyo.paper.event.server.ServerTickEndEvent
import com.destroystokyo.paper.event.server.ServerTickStartEvent
import org.bukkit.World as BukkitWorld
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.event.world.WorldUnloadEvent
import work.art1st.mineRails.bukkit.debug.TrainDebugLog
import work.art1st.mineRails.bukkit.extension.renderChunkManager
import work.art1st.mineRails.bukkit.extension.train.SeatExtension
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
        // 更新座位系统（跟随车厢移动 + 同步坐着的玩家）
        SeatExtension.tickAll()

        railWorlds.forEach { (world, railWorld) ->
            railWorld.tickManager.postTick()
            if (TrainDebugLog.enabled) {
                (railWorld as? CoreWorld)?.let { coreWorld ->
                    coreWorld.allTrains.forEach { train ->
                        train.cars.forEachIndexed { index, car ->
                            TrainDebugLog.logRow(listOf(
                                System.currentTimeMillis(), "physics", world.uid, train.id, index, car.id,
                                train.speed, train.isDerailed, train.controller?.javaClass?.simpleName ?: "",
                                train.head.location.rail.id, train.head.location.arcLength,
                                train.head.location.position.x(), train.head.location.position.y(), train.head.location.position.z(),
                                car.position.x(), car.position.y(), car.position.z(),
                                car.direction.x(), car.direction.y(), car.direction.z(),
                                car.normal.x(), car.normal.y(), car.normal.z(),
                                car.bogieA.position.x(), car.bogieA.position.y(), car.bogieA.position.z(),
                                car.bogieB.position.x(), car.bogieB.position.y(), car.bogieB.position.z(),
                                car.bogieA.direction.x(), car.bogieA.direction.y(), car.bogieA.direction.z(),
                                car.bogieB.direction.x(), car.bogieB.direction.y(), car.bogieB.direction.z(),
                                null, null, null, null, null, null, null,
                                "after_postTick_before_render"
                            ))
                        }
                    }
                }
            }
            railWorld.renderChunkManager.render()
        }
    }
}
