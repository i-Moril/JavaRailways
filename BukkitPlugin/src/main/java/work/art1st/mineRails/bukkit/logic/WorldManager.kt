package work.art1st.mineRails.bukkit.logic

import com.destroystokyo.paper.event.server.ServerTickEndEvent
import com.destroystokyo.paper.event.server.ServerTickStartEvent
import org.bukkit.World as BukkitWorld
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.event.world.WorldUnloadEvent
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.debug.AbnormalTrainMarker
import work.art1st.mineRails.bukkit.debug.TrainDebugLog
import work.art1st.mineRails.bukkit.extension.renderChunkManager
import work.art1st.mineRails.bukkit.extension.train.CarExtension
import work.art1st.mineRails.bukkit.extension.train.SeatExtension
import work.art1st.mineRails.bukkit.extension.train.TrainExtension
import work.art1st.mineRails.bukkit.persistence.WorldPersistence
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.physics.CoreWorld

class WorldManager(
    val railWorlds: MutableMap<BukkitWorld, World> = mutableMapOf()
) : Listener {

    fun registerRailWorld(bukkitWorld: BukkitWorld) {
        if (railWorlds.containsKey(bukkitWorld)) return
        val snapshot = WorldPersistence.tryLoadSnapshot(bukkitWorld)
        val railWorld = RailWorldFactory.createForBukkit(bukkitWorld, snapshot)
        railWorlds[bukkitWorld] = railWorld
        if (snapshot != null && railWorld is CoreWorld) {
            restoreBukkitTrainExtensions(railWorld, snapshot)
            markSkippedPersistedTrains(bukkitWorld, railWorld, snapshot)
        }
    }

    private fun restoreBukkitTrainExtensions(coreWorld: CoreWorld, snapshot: CoreWorld.SavedWorldV1) {
        for (savedTrain in snapshot.trains) {
            val train = coreWorld.getTrain(savedTrain.id) ?: continue
            val trainExtension = TrainExtension()
            train.registerExtensionData(BukkitPlugin.INSTANCE, trainExtension)
            trainExtension.initialize(train, savedTrain.extension)

            val savedCarsById = savedTrain.cars.associateBy { it.id }
            for (car in train.cars) {
                val savedCar = savedCarsById[car.id] ?: continue
                val carExtension = CarExtension()
                car.registerExtensionData(BukkitPlugin.INSTANCE, carExtension)
                carExtension.initialize(car, savedCar.config.extension)
            }

            runCatching {
                train.setLocation(train.head.location)
                train.commit()
            }.onFailure { error ->
                BukkitPlugin.INSTANCE.logger.warning(
                    "Failed to refresh restored train '${train.id}' render state: " +
                        "${error::class.simpleName}: ${error.message ?: ""}"
                )
                AbnormalTrainMarker.markTrain(train, "restore failed: ${error::class.simpleName}")
            }
        }
    }

    private fun markSkippedPersistedTrains(
        bukkitWorld: BukkitWorld,
        coreWorld: CoreWorld,
        snapshot: CoreWorld.SavedWorldV1
    ) {
        val loadedIds = coreWorld.allTrains.map { it.id }.toSet()
        snapshot.trains
            .filter { savedTrain -> savedTrain.id !in loadedIds }
            .forEach { savedTrain ->
                BukkitPlugin.INSTANCE.logger.warning(
                    "Persisted train '${savedTrain.id}' could not be loaded; showing abnormal train marker"
                )
                AbnormalTrainMarker.markSavedTrain(coreWorld, bukkitWorld, savedTrain, "load failed")
            }
    }

    fun unregisterRailWorld(bukkitWorld: BukkitWorld) {
        railWorlds[bukkitWorld]?.let { rw ->
            (rw as? CoreWorld)?.let { WorldPersistence.saveSnapshot(bukkitWorld, it) }
        }
        AbnormalTrainMarker.clearWorld(bukkitWorld)
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
            (railWorld as? CoreWorld)?.let { coreWorld ->
                AbnormalTrainMarker.tick(coreWorld, world)
            }
            railWorld.renderChunkManager.render()
        }
    }
}
