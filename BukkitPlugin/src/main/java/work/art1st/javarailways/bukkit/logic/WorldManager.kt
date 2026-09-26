package work.art1st.javarailways.bukkit.logic

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Location
import org.bukkit.World as BukkitWorld
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.event.world.WorldUnloadEvent
import work.art1st.javarailways.basic.pathFinding.basicWorldData
import work.art1st.javarailways.basic.api.ato.AtoStallReport
import work.art1st.javarailways.basic.safety.SpeedSafetyMonitor
import work.art1st.javarailways.basic.safety.TrainSpeedProfile
import work.art1st.javarailways.basic.persistence.BasicInfrastructureSnapshotV1
import work.art1st.javarailways.basic.persistence.BasicRuntimeSnapshotV2
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.api.internal.DefaultRailwayObservationService
import work.art1st.javarailways.bukkit.api.internal.DefaultInfrastructureResourceService
import work.art1st.javarailways.bukkit.api.internal.DefaultSignalEpochSourceService
import work.art1st.javarailways.basic.api.infrastructure.RailIntervalResource
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.bukkit.debug.AbnormalTrainMarker
import work.art1st.javarailways.bukkit.debug.JavaRailwaysServerTickMetrics
import work.art1st.javarailways.bukkit.debug.TrainDebugLog
import work.art1st.javarailways.bukkit.extension.renderChunkManager
import work.art1st.javarailways.bukkit.extension.train.CarExtension
import work.art1st.javarailways.bukkit.extension.train.carRendererOrNull
import work.art1st.javarailways.bukkit.extension.train.TrainExtension
import work.art1st.javarailways.bukkit.extension.train.rendererOrNull
import work.art1st.javarailways.bukkit.persistence.WorldPersistence
import work.art1st.javarailways.bukkit.persistence.WorldSnapshotCapture
import work.art1st.javarailways.simpleato.persistence.SimpleATORuntimeSnapshotV1
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.bukkit.util.BukkitThreadGuardBridge
import work.art1st.javarailways.bukkit.util.BukkitThreadOwnership
import work.art1st.javarailways.bukkit.util.LatestAffinityTaskDispatcher
import work.art1st.javarailways.bukkit.util.PlatformCommitAffinity
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.api.physics.train.CarFrameSnapshot
import work.art1st.javarailways.core.api.util.tick.Synchronizable
import work.art1st.javarailways.core.physics.train.CoreCar
import work.art1st.javarailways.core.util.ThreadGuard
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.logging.Level
import kotlin.system.measureNanoTime

data class CarFrameDispatchMetrics(
    val pendingCars: Int,
    val coalescedFrames: Long,
    val scheduledTasks: Long
)

class WorldManager internal constructor(
    val railWorlds: MutableMap<BukkitWorld, World> = ConcurrentHashMap(),
    private val observationService: DefaultRailwayObservationService? = null,
    private val infrastructureResourceService: DefaultInfrastructureResourceService? = null,
    private val signalEpochSourceService: DefaultSignalEpochSourceService? = null
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
    private var latestServerTickMetricsSnapshot: JavaRailwaysServerTickMetrics? = null
    private val recentServerTickMetrics: ArrayDeque<JavaRailwaysServerTickMetrics> = ArrayDeque()
    private val snapshotWriter: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "JavaRailways-world-snapshot-writer").apply { isDaemon = true }
    }
    private val pendingPeriodicSnapshots = ConcurrentHashMap<BukkitWorld, WorldSnapshotCapture>()
    private val snapshotWrites = ConcurrentHashMap<BukkitWorld, Future<*>>()
    private val speedSafetyMonitors = ConcurrentHashMap<CoreWorld, SpeedSafetyMonitor>()

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

    fun finishStopping() {
        snapshotWrites.forEach { (world, write) ->
            try {
                write.get()
            } catch (failure: Exception) {
                BukkitPlugin.INSTANCE.logger.log(
                    Level.SEVERE,
                    "Failed to finish snapshot write for world ${world.uid} (${world.name})",
                    failure
                )
            }
        }
        snapshotWriter.shutdown()
    }

    fun latestServerTickMetrics(): JavaRailwaysServerTickMetrics? = latestServerTickMetricsSnapshot

    fun carFrameDispatchMetrics(): CarFrameDispatchMetrics {
        val metrics = carFrameDispatcher.metrics()
        return CarFrameDispatchMetrics(
            pendingCars = metrics.activeKeys,
            coalescedFrames = metrics.coalescedValues,
            scheduledTasks = metrics.scheduledTasks
        )
    }

    fun recentServerTickMetrics(limit: Int = 20): List<JavaRailwaysServerTickMetrics> {
        if (limit <= 0) return emptyList()
        return recentServerTickMetrics.toList().takeLast(limit)
    }

    fun registerRailWorld(bukkitWorld: BukkitWorld) {
        ThreadGuard.ensureMainThread("WorldManager.registerRailWorld")
        if (railWorlds.containsKey(bukkitWorld)) return
        unloadingWorlds.remove(bukkitWorld)

        val split = when (val result = WorldPersistence.loadSplitSnapshots(bukkitWorld)) {
            WorldPersistence.SplitSnapshotLoadResult.Missing -> null
            is WorldPersistence.SplitSnapshotLoadResult.Loaded -> result.snapshots
            is WorldPersistence.SplitSnapshotLoadResult.Corrupted -> {
                BukkitPlugin.INSTANCE.logger.severe(
                    "Failed to load split JavaRailways snapshots for world '${bukkitWorld.name}' from " +
                        "${result.files.joinToString { it.path }}; features are disabled until fixed. " +
                        "${result.error::class.simpleName}: ${result.error.message.orEmpty()}"
                )
                return
            }
        }
        val splitInfrastructure = split?.infrastructure
        val splitRuntime = split?.runtime
        val railWorld = RailWorldFactory.createForBukkit(
            bukkitWorld = bukkitWorld,
            infrastructure = splitInfrastructure?.core(),
            runtime = splitRuntime?.core(),
            basicInfrastructure = splitInfrastructure?.basic(),
            basicRuntime = splitRuntime?.basic()
        )
        try {
            if (railWorld is CoreWorld) {
                infrastructureResourceService?.registerWorld(
                    bukkitWorld.uid,
                    railWorld::topologyRevision,
                    railExists = { railId -> railWorld.getRail(railId) != null },
                    topologyNodeExists = { key -> railWorld.topologyState.structuralNodeWithPorts(key.ports) != null },
                    resourceExists = { resource ->
                    when (resource) {
                        is RailIntervalResource -> railWorld.getRail(resource.railId)?.curve?.totalArcLength
                            ?.let { resource.endAbMeters <= it } == true
                        is StableInfrastructureResource ->
                            infrastructureResourceService.stableResourceExists(bukkitWorld.uid, resource)
                    }
                    }
                )
                infrastructureResourceService?.attachInfrastructureRuntime(
                    worldId = bukkitWorld.uid,
                    world = railWorld,
                    latestObservation = {
                        observationService?.latest(bukkitWorld.uid)?.orElse(null)
                    },
                    awaitCurrentPhysics = { railWorld.awaitCurrentPhysicsEpoch() },
                    turnoutLockingDistanceMeters = railWorld.basicWorldData
                        .turnoutSafetyDefaults.lockingDistanceMeters,
                    stallWorld = { reason, failure ->
                        stallWorldRuntime(bukkitWorld, railWorld, reason, failure)
                    }
                )
                splitRuntime?.simpleAto()?.restore(railWorld)
                val simpleAtoData = railWorld.simpleAtoWorldData
                signalEpochSourceService?.registerWorld(
                    worldId = bukkitWorld.uid,
                    runtime = simpleAtoData.signalEpochRuntime,
                    inspectionRuntime = simpleAtoData.inspectionRuntime,
                    activeRestrictionStreams = simpleAtoData.signalRestrictions::activeRestrictionStreams,
                    stallWorld = { reason, failure ->
                        stallWorldRuntime(bukkitWorld, railWorld, reason, failure)
                    }
                )
                val config = BukkitPlugin.INSTANCE.physicsConfig.overspeed
                val monitor = SpeedSafetyMonitor(
                    railWorld,
                    SpeedSafetyMonitor.Config(
                        derailEnabled = config.derailEnabled,
                        toleranceMetersPerSecond = config.toleranceKmH / 3.6,
                        graceTicks = config.graceTicks
                    )
                )
                speedSafetyMonitors[railWorld] = monitor
                railWorld.physicsCoordinator.registerPublishedFrameProcessor(monitor)
            }
            val restoredTrains = split?.runtime?.core()?.trains.orEmpty()
            if (restoredTrains.isNotEmpty() && railWorld is CoreWorld) {
                restoreBukkitTrainExtensions(railWorld, restoredTrains)
                refreshTrainSpeedProfiles(railWorld)
                markSkippedPersistedTrains(bukkitWorld, railWorld, restoredTrains)
            }
            (railWorld as? CoreWorld)?.physicsCoordinator?.start()
            railWorlds[bukkitWorld] = railWorld
        } catch (failure: Exception) {
            signalEpochSourceService?.removeWorld(bukkitWorld.uid)
            infrastructureResourceService?.removeWorld(bukkitWorld.uid)
            observationService?.removeWorld(bukkitWorld.uid)
            (railWorld as? CoreWorld)?.let { coreWorld ->
                speedSafetyMonitors.remove(coreWorld)
                    ?.let(coreWorld.physicsCoordinator::unregisterPublishedFrameProcessor)
                coreWorld.physicsCoordinator.close()
            }
            runCatching { railWorld.renderChunkManager.close() }
            runCatching { (railWorld.tickManager as? AutoCloseable)?.close() }
            throw failure
        }
    }

    private fun stallWorldRuntime(
        bukkitWorld: BukkitWorld,
        coreWorld: CoreWorld,
        reason: String,
        failure: Exception
    ) {
        val observation = observationService?.latest(bukkitWorld.uid)?.orElse(null)
        coreWorld.basicWorldData.atoCoordinator.stall(
            AtoStallReport(
                worldId = bukkitWorld.uid.toString(),
                trainId = "<infrastructure>",
                tick = observation?.stamp?.physicsEpoch ?: 0L,
                errorType = failure::class.qualifiedName ?: failure.javaClass.name,
                message = "$reason: ${failure.message.orEmpty()}",
                stackTrace = failure.stackTraceToString(),
                initialTrain = null,
                currentTrain = null,
                route = null,
                perception = null,
                activeProviderId = null,
                constraints = emptyMap()
            )
        )
    }

    private fun restoreBukkitTrainExtensions(
        coreWorld: CoreWorld,
        savedTrains: List<work.art1st.javarailways.core.physics.train.SavedTrainV1>
    ) {
        for (savedTrain in savedTrains) {
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

        }
    }

    fun refreshTrainSpeedProfiles(coreWorld: CoreWorld) {
        coreWorld.allTrains.forEach { train ->
            val control = runCatching { train.rendererOrNull?.resolveControl() }.getOrNull()
            if (control == null) {
                coreWorld.basicWorldData.trainSpeedProfiles.remove(train.id)
            } else {
                coreWorld.basicWorldData.trainSpeedProfiles.put(
                    train.id,
                    TrainSpeedProfile(control.curveSpeedLimitOffset)
                )
            }
        }
    }

    private fun markSkippedPersistedTrains(
        bukkitWorld: BukkitWorld,
        coreWorld: CoreWorld,
        savedTrains: List<work.art1st.javarailways.core.physics.train.SavedTrainV1>
    ) {
        val loadedIds = coreWorld.allTrains.map { it.id }.toSet()
        savedTrains
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
                val ticket = coreWorld?.physicsCoordinator?.captureCurrentTicket()
                ticket?.await()
                coreWorld?.let {
                    val snapshots = captureSnapshots(it, ticket?.epoch ?: 0L)
                    snapshotWrites.remove(bukkitWorld)?.get()
                    val worldId = bukkitWorld.uid
                    snapshotWriter.submit {
                        WorldPersistence.saveSplitSnapshots(worldId, snapshots)
                    }.get()
                }
            } finally {
                coreWorld?.let { world ->
                    speedSafetyMonitors.remove(world)?.let(world.physicsCoordinator::unregisterPublishedFrameProcessor)
                }
                infrastructureResourceService?.removeWorld(bukkitWorld.uid)
                signalEpochSourceService?.removeWorld(bukkitWorld.uid)
                observationService?.removeWorld(bukkitWorld.uid)
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
            var publishedPhysicsEpoch = 0L
            (railWorld as? CoreWorld)?.let { coreWorld ->
                // Capture once: if the fallback timer currently owns an epoch, TickEnd must wait
                // for that exact ticket instead of rendering an older published state.
                val physicsTicket = coreWorld.physicsCoordinator.captureCurrentTicket()
                val physicsWaitNanos = measureNanoTime { physicsTicket.await() }
                publishedPhysicsEpoch = physicsTicket.epoch
                phaseBreakdown["world:${world.name}:physics_wait"] = physicsWaitNanos
                blockedWaitAsyncNanos += physicsWaitNanos

                val observationPublishNanos = measureNanoTime {
                    val observation = observationService?.publish(
                        world.uid,
                        coreWorld.latestWorldFrame(),
                        physicsTicket.epoch
                    )
                    if (observation != null) {
                        infrastructureResourceService?.consumeObservation(world.uid, observation)
                    }
                }
                phaseBreakdown["world:${world.name}:observation_publish"] = observationPublishNanos
                computeNanos += observationPublishNanos

                val speedSafetyNanos = measureNanoTime {
                    coreWorld.publishPendingDerailEvents()
                    speedSafetyMonitors[coreWorld]
                        ?.drainRequests(publishedPhysicsEpoch)
                        .orEmpty()
                        .forEach { request ->
                            val train = coreWorld.getTrain(request.trainId) ?: return@forEach
                            if (train.publishedFrame.physicsEpoch == request.observedPhysicsEpoch) {
                                train.derail(request.reason)
                            }
                        }
                }
                phaseBreakdown["world:${world.name}:speed_safety"] = speedSafetyNanos
                computeNanos += speedSafetyNanos

                val renderCommitNanos = measureNanoTime {
                    dispatchPublishedTrainFrames(world, coreWorld)
                }
                phaseBreakdown["world:${world.name}:train_visual_frame_consume"] = renderCommitNanos
                computeNanos += renderCommitNanos
            }

            val timetableDispatcherNanos = measureNanoTime {
                railWorld.simpleAtoWorldData.dispatcher.tick(railWorld)
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

            if (serverTickIndex % PERIODIC_SNAPSHOT_TICKS == 0L) {
                (railWorld as? CoreWorld)?.let { coreWorld ->
                    enqueuePeriodicSnapshot(world, captureSnapshots(coreWorld, publishedPhysicsEpoch))
                }
            }
        }

        rememberServerTickMetrics(
            JavaRailwaysServerTickMetrics(
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
                        revision = trainFrame.revision,
                        trainSpeedMetersPerSecond = trainFrame.speedMetersPerSecond
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
                    pending.car.carRendererOrNull?.applyFrame(
                        frame,
                        pending.revision,
                        pending.trainSpeedMetersPerSecond
                    )
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

    private fun captureSnapshots(coreWorld: CoreWorld, physicsEpoch: Long): WorldSnapshotCapture {
        val revision = serverTickIndex.coerceAtLeast(0L)
        val coreInfrastructure = coreWorld.saveInfrastructure(revision)
        val basicInfrastructure = BasicInfrastructureSnapshotV1.capture(coreWorld, revision)
        val coreRuntime = coreWorld.saveRuntime(revision, physicsEpoch.coerceAtLeast(0L))
        val basicRuntime = BasicRuntimeSnapshotV2.capture(
            coreWorld,
            revision,
            physicsEpoch.coerceAtLeast(0L)
        )
        val simpleAtoRuntime = SimpleATORuntimeSnapshotV1.capture(
            coreWorld,
            revision,
            physicsEpoch.coerceAtLeast(0L)
        )
        return WorldSnapshotCapture(
            coreInfrastructure,
            basicInfrastructure,
            coreRuntime,
            basicRuntime,
            simpleAtoRuntime
        )
    }

    @Synchronized
    private fun enqueuePeriodicSnapshot(world: BukkitWorld, snapshots: WorldSnapshotCapture) {
        pendingPeriodicSnapshots[world] = snapshots
        if (snapshotWrites[world]?.isDone == false) return
        startSnapshotWrite(world, world.uid, world.name)
    }

    @Synchronized
    private fun startSnapshotWrite(world: BukkitWorld, worldId: java.util.UUID, worldName: String) {
        snapshotWrites[world] = snapshotWriter.submit {
            while (true) {
                val latest = synchronized(this) { pendingPeriodicSnapshots.remove(world) }
                if (latest == null) {
                    synchronized(this) {
                        snapshotWrites.remove(world)
                        if (pendingPeriodicSnapshots.containsKey(world)) {
                            startSnapshotWrite(world, worldId, worldName)
                        }
                    }
                    return@submit
                }
                runCatching { WorldPersistence.saveSplitSnapshots(worldId, latest) }
                    .onFailure { error ->
                        BukkitPlugin.INSTANCE.logger.severe(
                            "Failed to save periodic snapshot for '$worldName': " +
                                "${error::class.simpleName}: ${error.message.orEmpty()}"
                        )
                    }
            }
        }
    }

    private fun <T> withSimulationContext(action: () -> T): T =
        BukkitThreadGuardBridge.runInSimulationContext(action)

    private companion object {
        const val PERIODIC_SNAPSHOT_TICKS = 1_200L
    }

    private data class PendingCarFrame(
        val bukkitWorld: BukkitWorld,
        val car: CoreCar,
        val frame: CarFrameSnapshot,
        val revision: Long,
        val trainSpeedMetersPerSecond: Double
    )

    private data class PendingPlatformCommit(
        val synchronizable: Synchronizable,
        val location: Location,
        val action: () -> Unit
    )


    private fun rememberServerTickMetrics(snapshot: JavaRailwaysServerTickMetrics) {
        latestServerTickMetricsSnapshot = snapshot
        recentServerTickMetrics.addLast(snapshot)
        while (recentServerTickMetrics.size > 200) {
            recentServerTickMetrics.removeFirst()
        }
    }
}
