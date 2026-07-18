package work.art1st.mineRails.bukkit.logic

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Location
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
import work.art1st.mineRails.bukkit.extension.train.TrainExtension
import work.art1st.mineRails.bukkit.persistence.WorldPersistence
import work.art1st.mineRails.bukkit.util.BukkitThreadGuardBridge
import work.art1st.mineRails.bukkit.util.BukkitThreadOwnership
import work.art1st.mineRails.bukkit.util.LatestAffinityTaskDispatcher
import work.art1st.mineRails.bukkit.util.PlatformCommitAffinity
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.physics.CoreWorld
import work.art1st.mineRails.core.api.physics.train.CarFrameSnapshot
import work.art1st.mineRails.core.api.util.tick.Synchronizable
import work.art1st.mineRails.core.physics.train.CoreCar
import work.art1st.mineRails.core.util.ThreadGuard
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.measureNanoTime

data class CarFrameDispatchMetrics(
    val pendingCars: Int,
    val coalescedFrames: Long,
    val scheduledTasks: Long
)

class WorldManager(
    val railWorlds: MutableMap<BukkitWorld, World> = mutableMapOf()
) : Listener {
    private var serverTickIndex: Long = 0L
    private val unloadingWorlds: MutableSet<BukkitWorld> = ConcurrentHashMap.newKeySet()
    private var tickTask: ScheduledTask? = null
    @Volatile
    private var stopping: Boolean = false
    private val carFrameDispatcher = LatestAffinityTaskDispatcher<CoreCar, PendingCarFrame>(::scheduleCarFrame)
    private val platformCommitDispatcher =
        LatestAffinityTaskDispatcher<Synchronizable, PendingPlatformCommit>(::schedulePlatformCommit)
    @Volatile
    private var latestServerTickMetricsSnapshot: MineRailsServerTickMetrics? = null
    private val recentServerTickMetrics: ArrayDeque<MineRailsServerTickMetrics> = ArrayDeque()

    fun start() {
        check(tickTask == null) { "WorldManager is already started" }
        stopping = false
        tickTask = BukkitPlugin.INSTANCE.platformDispatcher.runGlobalAtFixedRate(1L, 1L) {
            withSimulationContext(::tick)
        }
    }

    fun stop() {
        stopping = true
        unloadingWorlds.addAll(railWorlds.keys)
        tickTask?.cancel()
        tickTask = null
        carFrameDispatcher.clear()
        platformCommitDispatcher.clear()
    }

    fun latestServerTickMetrics(): MineRailsServerTickMetrics? = latestServerTickMetricsSnapshot

    fun carFrameDispatchMetrics(): CarFrameDispatchMetrics {
        val metrics = carFrameDispatcher.metrics()
        return CarFrameDispatchMetrics(
            pendingCars = metrics.activeKeys,
            coalescedFrames = metrics.coalescedValues,
            scheduledTasks = metrics.scheduledTasks
        )
    }

    fun recentServerTickMetrics(limit: Int = 20): List<MineRailsServerTickMetrics> {
        if (limit <= 0) return emptyList()
        return recentServerTickMetrics.toList().takeLast(limit)
    }

    fun registerRailWorld(bukkitWorld: BukkitWorld) {
        ThreadGuard.ensureMainThread("WorldManager.registerRailWorld")
        if (railWorlds.containsKey(bukkitWorld)) return
        unloadingWorlds.remove(bukkitWorld)

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
        unloadingWorlds.add(bukkitWorld)
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
        withSimulationContext { registerRailWorld(event.world) }
    }

    @EventHandler
    fun onWorldUnload(event: WorldUnloadEvent) {
        withSimulationContext { unregisterRailWorld(event.world) }
    }

    private fun tick() {
        tickStart()
        tickEnd()
    }

    private fun tickStart() {
        ThreadGuard.ensureMainThread("WorldManager.tickStart")
        serverTickIndex += 1L
        val currentTimeMs = System.currentTimeMillis()
        railWorlds.forEach { (_, railWorld) ->
            (railWorld as? CoreWorld)?.physicsCoordinator?.wakeFromMainThread()
            railWorld.tickManager.preTick(currentTimeMs)
        }
    }

    private fun tickEnd() {
        ThreadGuard.ensureMainThread("WorldManager.tickEnd")
        val phaseBreakdown = linkedMapOf<String, Long>()
        var computeNanos = 0L
        var blockedWaitAsyncNanos = 0L

        railWorlds.forEach { (world, railWorld) ->
            (railWorld as? CoreWorld)?.let { coreWorld ->
                // Capture once: if the fallback timer currently owns an epoch, TickEnd must wait
                // for that exact ticket instead of rendering an older published state.
                val physicsTicket = coreWorld.physicsCoordinator.captureCurrentTicket()
                val physicsWaitNanos = measureNanoTime { physicsTicket.await() }
                phaseBreakdown["world:${world.name}:physics_wait"] = physicsWaitNanos
                blockedWaitAsyncNanos += physicsWaitNanos

                val renderCommitNanos = measureNanoTime {
                    dispatchPublishedTrainFrames(world, coreWorld)
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

            val platformCommitNanos = measureNanoTime {
                railWorld.tickManager.drainPendingCommits().forEach { synchronizable ->
                    val action: () -> Unit = {
                        runCatching { synchronizable.commit() }.onFailure { error ->
                            BukkitPlugin.INSTANCE.logger.severe(
                                "Tick platform commit failed in world '${world.name}': " +
                                    "${error::class.simpleName}: ${error.message.orEmpty()}"
                            )
                        }
                        Unit
                    }
                    val location = (synchronizable as? PlatformCommitAffinity)?.commitLocation()
                    if (location == null) {
                        action()
                    } else {
                        queuePlatformCommit(PendingPlatformCommit(synchronizable, location, action))
                    }
                }
            }
            phaseBreakdown["world:${world.name}:tick_platform_commit_dispatch"] = platformCommitNanos
            computeNanos += platformCommitNanos

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

    /** Keeps at most one in-flight and one latest pending region task per car. */
    private fun dispatchPublishedTrainFrames(bukkitWorld: BukkitWorld, coreWorld: CoreWorld) {
        coreWorld.drainPublishedTrainFrames().forEach { trainFrame ->
            val train = coreWorld.getTrain(trainFrame.trainId) ?: return@forEach
            if (train.cars.size != trainFrame.cars.size) return@forEach
            if (trainFrame.cars.indices.any { train.cars[it].id != trainFrame.cars[it].carId }) return@forEach
            for (index in trainFrame.cars.indices) {
                queueCarFrame(
                    PendingCarFrame(
                        bukkitWorld = bukkitWorld,
                        car = train.cars[index],
                        frame = trainFrame.cars[index],
                        revision = trainFrame.revision
                    )
                )
            }
        }
    }

    private fun queueCarFrame(pending: PendingCarFrame) {
        carFrameDispatcher.offer(pending.car, pending)
    }

    private fun scheduleCarFrame(pending: PendingCarFrame, complete: () -> Unit) {
        val frame = pending.frame
        val location = Location(
            pending.bukkitWorld,
            frame.state.positionX,
            frame.state.positionY,
            frame.state.positionZ
        )
        BukkitPlugin.INSTANCE.platformDispatcher.runAt(location) {
            try {
                if (!stopping && pending.bukkitWorld !in unloadingWorlds) {
                    pending.car.carRendererOrNull?.applyFrame(frame, pending.revision)
                }
            } finally {
                complete.invoke()
            }
        }
    }

    private fun queuePlatformCommit(pending: PendingPlatformCommit) {
        platformCommitDispatcher.offer(pending.synchronizable, pending)
    }

    private fun schedulePlatformCommit(pending: PendingPlatformCommit, complete: () -> Unit) {
        BukkitPlugin.INSTANCE.platformDispatcher.runAt(pending.location) platformTask@{
            if (stopping || pending.location.world in unloadingWorlds) {
                complete.invoke()
                return@platformTask
            }
            val currentLocation =
                (pending.synchronizable as? PlatformCommitAffinity)?.commitLocation()
            if (currentLocation != null && !BukkitThreadOwnership.isOwnedByCurrentRegion(currentLocation)) {
                schedulePlatformCommit(pending.copy(location = currentLocation), complete)
                return@platformTask
            }
            try {
                pending.action.invoke()
            } finally {
                complete.invoke()
            }
        }
    }

    private fun <T> withSimulationContext(action: () -> T): T =
        BukkitThreadGuardBridge.runInSimulationContext(action)

    private data class PendingCarFrame(
        val bukkitWorld: BukkitWorld,
        val car: CoreCar,
        val frame: CarFrameSnapshot,
        val revision: Long
    )

    private data class PendingPlatformCommit(
        val synchronizable: Synchronizable,
        val location: Location,
        val action: () -> Unit
    )


    private fun rememberServerTickMetrics(snapshot: MineRailsServerTickMetrics) {
        latestServerTickMetricsSnapshot = snapshot
        recentServerTickMetrics.addLast(snapshot)
        while (recentServerTickMetrics.size > 200) {
            recentServerTickMetrics.removeFirst()
        }
    }
}
