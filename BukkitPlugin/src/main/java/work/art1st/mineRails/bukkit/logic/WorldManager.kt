package work.art1st.mineRails.bukkit.logic

import com.destroystokyo.paper.event.server.ServerTickEndEvent
import com.destroystokyo.paper.event.server.ServerTickStartEvent
import org.bukkit.World as BukkitWorld
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.event.world.WorldUnloadEvent
import work.art1st.mineRails.basic.pathFinding.basicWorldData
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.debug.AbnormalTrainMarker
import work.art1st.mineRails.bukkit.debug.MineRailsServerTickMetrics
import work.art1st.mineRails.bukkit.debug.TrainDebugLog
import work.art1st.mineRails.bukkit.extension.renderChunkManager
import work.art1st.mineRails.bukkit.extension.train.CarExtension
import work.art1st.mineRails.bukkit.extension.train.carRendererOrNull
import work.art1st.mineRails.bukkit.extension.train.SeatExtension
import work.art1st.mineRails.bukkit.extension.train.TrainExtension
import work.art1st.mineRails.bukkit.persistence.WorldPersistence
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.physics.CoreWorld
import work.art1st.mineRails.core.physics.train.CoreCar
import work.art1st.mineRails.core.util.ThreadGuard
import java.util.ArrayDeque
import kotlin.system.measureNanoTime

class WorldManager(
    val railWorlds: MutableMap<BukkitWorld, World> = mutableMapOf()
) : Listener {
    private var serverTickIndex: Long = 0L
    @Volatile
    private var latestServerTickMetricsSnapshot: MineRailsServerTickMetrics? = null
    private val recentServerTickMetrics: ArrayDeque<MineRailsServerTickMetrics> = ArrayDeque()

    fun latestServerTickMetrics(): MineRailsServerTickMetrics? = latestServerTickMetricsSnapshot

    fun recentServerTickMetrics(limit: Int = 20): List<MineRailsServerTickMetrics> {
        if (limit <= 0) return emptyList()
        return recentServerTickMetrics.toList().takeLast(limit)
    }

    fun registerRailWorld(bukkitWorld: BukkitWorld) {
        ThreadGuard.ensureMainThread("WorldManager.registerRailWorld")
        if (railWorlds.containsKey(bukkitWorld)) return

        val snapshot = when (val result = WorldPersistence.loadSnapshot(bukkitWorld)) {
            WorldPersistence.SnapshotLoadResult.Missing -> null
            is WorldPersistence.SnapshotLoadResult.Loaded -> result.snapshot
            is WorldPersistence.SnapshotLoadResult.Corrupted -> {
                BukkitPlugin.INSTANCE.logger.severe(
                    "Failed to load MineRails snapshot for world '${bukkitWorld.name}' from ${result.file.path}; " +
                        "features are disabled for this world until the snapshot is fixed. " +
                        "${result.error::class.simpleName}: ${result.error.message ?: ""}"
                )
                return
            }
        }

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
        ThreadGuard.ensureMainThread("WorldManager.unregisterRailWorld")
        railWorlds[bukkitWorld]?.let { rw ->
            val coreWorld = rw as? CoreWorld
            try {
                coreWorld?.physicsCoordinator?.captureCurrentTicket()?.await()
                coreWorld?.let { WorldPersistence.saveSnapshot(bukkitWorld, it) }
            } finally {
                coreWorld?.physicsCoordinator?.close()
                try {
                    rw.renderChunkManager.close()
                } finally {
                    (rw.tickManager as? AutoCloseable)?.close()
                }
            }
        }
        AbnormalTrainMarker.clearWorld(bukkitWorld)
        railWorlds.remove(bukkitWorld)
    }

    @EventHandler
    fun onWorldLoad(event: WorldLoadEvent) {
        ThreadGuard.ensureMainThread("WorldManager.onWorldLoad")
        registerRailWorld(event.world)
    }

    @EventHandler
    fun onWorldUnload(event: WorldUnloadEvent) {
        ThreadGuard.ensureMainThread("WorldManager.onWorldUnload")
        unregisterRailWorld(event.world)
    }

    @EventHandler
    fun onTickStart(event: ServerTickStartEvent) {
        ThreadGuard.ensureMainThread("WorldManager.onTickStart")
        serverTickIndex += 1L
        val currentTimeMs = System.currentTimeMillis()
        railWorlds.forEach { (_, railWorld) ->
            (railWorld as? CoreWorld)?.physicsCoordinator?.wakeFromMainThread()
            railWorld.tickManager.preTick(currentTimeMs)
        }
    }

    @EventHandler
    fun onTickEnd(event: ServerTickEndEvent) {
        ThreadGuard.ensureMainThread("WorldManager.onTickEnd")
        val phaseBreakdown = linkedMapOf<String, Long>()
        var computeNanos = 0L
        var blockedWaitAsyncNanos = 0L

        // 更新座位系统（跟随车厢移动 + 同步坐着的玩家）
        val seatExtensionNanos = measureNanoTime {
            SeatExtension.tickAll()
        }
        phaseBreakdown["seat_extension"] = seatExtensionNanos
        computeNanos += seatExtensionNanos

        railWorlds.forEach { (world, railWorld) ->
            (railWorld as? CoreWorld)?.let { coreWorld ->
                // Capture once: if the fallback timer currently owns an epoch, TickEnd must wait
                // for that exact ticket instead of rendering an older published state.
                val physicsTicket = coreWorld.physicsCoordinator.captureCurrentTicket()
                val physicsWaitNanos = measureNanoTime { physicsTicket.await() }
                phaseBreakdown["world:${world.name}:physics_wait"] = physicsWaitNanos
                blockedWaitAsyncNanos += physicsWaitNanos

                val renderCommitNanos = measureNanoTime {
                    applyPublishedTrainFrames(coreWorld)
                }
                phaseBreakdown["world:${world.name}:train_visual_frame_consume"] = renderCommitNanos
                computeNanos += renderCommitNanos
            }

            val timetableDispatcherNanos = measureNanoTime {
                railWorld.basicWorldData.dispatcher.tick(railWorld)
            }
            phaseBreakdown["world:${world.name}:timetable_dispatcher"] = timetableDispatcherNanos
            computeNanos += timetableDispatcherNanos

            railWorld.tickManager.postTick()
            railWorld.tickManager.latestMainThreadTickMetrics()?.let { metrics ->
                computeNanos += metrics.computeNanos
                blockedWaitAsyncNanos += metrics.blockedWaitAsyncNanos
                metrics.phaseBreakdownNanos.forEach { (phase, nanos) ->
                    phaseBreakdown["world:${world.name}:$phase"] = nanos
                }
            }

            if (TrainDebugLog.enabled) {
                val debugLogNanos = measureNanoTime {
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
                phaseBreakdown["world:${world.name}:train_debug_log"] = debugLogNanos
                computeNanos += debugLogNanos
            }

            val abnormalMarkerNanos = measureNanoTime {
                (railWorld as? CoreWorld)?.let { coreWorld ->
                    AbnormalTrainMarker.tick(coreWorld, world)
                }
            }
            phaseBreakdown["world:${world.name}:abnormal_train_marker"] = abnormalMarkerNanos
            computeNanos += abnormalMarkerNanos
        }

        rememberServerTickMetrics(
            MineRailsServerTickMetrics(
                tickIndex = serverTickIndex,
                computeNanos = computeNanos,
                blockedWaitAsyncNanos = blockedWaitAsyncNanos,
                totalImpactNanos = computeNanos + blockedWaitAsyncNanos,
                phaseBreakdownNanos = phaseBreakdown.toMap()
            )
        )
    }

    /** Transitional Paper consumer; Folia will dispatch each car frame to its owning context. */
    private fun applyPublishedTrainFrames(coreWorld: CoreWorld) {
        coreWorld.drainPublishedTrainFrames().forEach { trainFrame ->
            val train = coreWorld.getTrain(trainFrame.trainId) ?: return@forEach
            val carsById = train.cars
                .filterIsInstance<CoreCar>()
                .associateBy { it.id }
            trainFrame.cars.forEach carFrameLoop@{ carFrame ->
                val car = carsById[carFrame.carId] ?: return@carFrameLoop
                car.carRendererOrNull?.applyFrame(carFrame, trainFrame.revision)
            }
        }
    }

    private fun rememberServerTickMetrics(snapshot: MineRailsServerTickMetrics) {
        latestServerTickMetricsSnapshot = snapshot
        recentServerTickMetrics.addLast(snapshot)
        while (recentServerTickMetrics.size > 200) {
            recentServerTickMetrics.removeFirst()
        }
    }
}
