package work.art1st.javarailways.bukkit.logic

import work.art1st.javarailways.bukkit.api.internal.DefaultStationCallResolverService

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Location
import org.bukkit.World as BukkitWorld
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.event.world.WorldUnloadEvent
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.simpleato.api.ato.AtoStallReport
import work.art1st.javarailways.basic.safety.SpeedSafetyMonitor
import work.art1st.javarailways.basic.safety.TrainSpeedProfile
import work.art1st.javarailways.basic.persistence.BasicInfrastructureSnapshotV2
import work.art1st.javarailways.basic.persistence.BasicRuntimeSnapshotV3
import work.art1st.javarailways.bukkit.api.diagnostics.WorldIssueInput
import work.art1st.javarailways.bukkit.api.diagnostics.WorldIssueKind
import work.art1st.javarailways.bukkit.behavior.behaviorRelativeTail
import work.art1st.javarailways.bukkit.config.model.DriverControlEnd
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.passenger.PassengerMovementManager
import work.art1st.javarailways.bukkit.api.internal.DefaultRailwayObservationService
import work.art1st.javarailways.bukkit.api.internal.DefaultInfrastructureResourceService
import work.art1st.javarailways.bukkit.api.internal.DefaultSignalEpochSourceService
import work.art1st.javarailways.bukkit.api.internal.DefaultModelPresentationService
import work.art1st.javarailways.core.api.physics.infrastructure.RailIntervalResource
import work.art1st.javarailways.core.api.physics.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.bukkit.debug.AbnormalTrainMarker
import work.art1st.javarailways.bukkit.debug.JavaRailwaysServerTickMetrics
import work.art1st.javarailways.bukkit.debug.TrainDebugLog
import work.art1st.javarailways.bukkit.extension.renderChunkManager
import work.art1st.javarailways.bukkit.extension.train.OperationalPartRenderProjection
import work.art1st.javarailways.bukkit.extension.train.projectOperationalPartRender
import work.art1st.javarailways.simpleato.api.station.StationId
import work.art1st.javarailways.bukkit.extension.train.TrainPartRenderSnapshot
import work.art1st.javarailways.bukkit.extension.train.TrainPartRenderSampleCache
import work.art1st.javarailways.bukkit.extension.train.CarExtension
import work.art1st.javarailways.bukkit.extension.train.carRendererOrNull
import work.art1st.javarailways.bukkit.extension.bukkitWorldData
import work.art1st.javarailways.bukkit.extension.train.TrainExtension
import work.art1st.javarailways.bukkit.extension.train.rendererOrNull
import work.art1st.javarailways.bukkit.persistence.WorldPersistence
import work.art1st.javarailways.bukkit.persistence.WorldSnapshotCapture
import work.art1st.javarailways.simpleato.persistence.SimpleATORuntimeSnapshotV1
import work.art1st.javarailways.simpleato.persistence.SimpleATOInfrastructureSnapshotV1
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.simpleAtoWorldDataOrNull
import work.art1st.javarailways.bukkit.util.BukkitThreadGuardBridge
import work.art1st.javarailways.bukkit.util.LiveWorldManagement
import work.art1st.javarailways.bukkit.util.BukkitThreadOwnership
import work.art1st.javarailways.bukkit.util.LatestAffinityTaskDispatcher
import work.art1st.javarailways.bukkit.util.PlatformCommitAffinity
import work.art1st.javarailways.bukkit.util.PlatformCleanup
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.tick.RailwaySimulationHalt
import work.art1st.javarailways.core.physics.tick.PhysicsTicket
import work.art1st.javarailways.core.util.TickManagerImpl
import work.art1st.javarailways.core.api.physics.train.CarFrameSnapshot
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.physics.train.CoreTrain
import work.art1st.javarailways.core.api.util.tick.Synchronizable
import work.art1st.javarailways.core.physics.train.CoreCar
import work.art1st.javarailways.core.api.threading.ThreadGuard
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import kotlin.system.measureNanoTime

data class TrainFrameDispatchMetrics(
    val pendingTrains: Int,
    val coalescedFrames: Long,
    val scheduledFrames: Long
)

class WorldManager internal constructor(
    val railWorlds: MutableMap<BukkitWorld, World> = ConcurrentHashMap(),
    private val observationService: DefaultRailwayObservationService? = null,
    private val infrastructureResourceService: DefaultInfrastructureResourceService? = null,
    private val signalEpochSourceService: DefaultSignalEpochSourceService? = null,
    private val stationCallResolverService: DefaultStationCallResolverService? = null,
    private val loadedWorlds: LoadedRailWorldRegistry = LoadedRailWorldRegistry(railWorlds),
    private val modelPresentationService: DefaultModelPresentationService? = null,
    private val releaseManualControl: (CoreWorld) -> Unit = {},
    private val retireManualControl: (CoreWorld) -> Unit = {}
) : Listener {
    /** Value-only lookup: no Bukkit access, owner dispatch or cross-world simulation lock. */
    internal fun loadedRailWorld(worldId: UUID): World? = loadedWorlds.find(worldId)

    private var serverTickIndex: Long = 0L
    private val unloadingWorlds: MutableSet<BukkitWorld> = ConcurrentHashMap.newKeySet()
    @Volatile
    private var stopping: Boolean = false
    private val trainFrameDispatcher = LatestAffinityTaskDispatcher<UUID, PendingTrainFrame>(::scheduleTrainFrame)
    private val platformCommitDispatcher =
        LatestAffinityTaskDispatcher<Synchronizable, PendingPlatformCommit>(::schedulePlatformCommit)
    @Volatile
    private var latestServerTickMetricsSnapshot: JavaRailwaysServerTickMetrics? = null
    private val recentServerTickMetrics: ArrayDeque<JavaRailwaysServerTickMetrics> = ArrayDeque()
    private val snapshotWriter: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "JavaRailways-world-snapshot-writer").apply { isDaemon = true }
    }
    private val pendingPeriodicSnapshots = ConcurrentHashMap<BukkitWorld, WorldSnapshotCapture>()
    private val snapshotRevisions = ConcurrentHashMap<CoreWorld, Long>()
    private val partRenderClocks = ConcurrentHashMap<CoreWorld, PartRenderSamplingClock>()
    private val partRenderSamples = ConcurrentHashMap<CoreWorld, TrainPartRenderSampleCache>()
    private val snapshotWrites = ConcurrentHashMap<BukkitWorld, Future<*>>()
    private val worldTickRunners = WorldTickRunnerRegistry<World>()
    private val worldTickMetrics = ConcurrentHashMap<BukkitWorld, JavaRailwaysServerTickMetrics>()

    private val startup = ServerPhysicsStartup(
        scheduleTicks = { action ->
            BukkitPlugin.INSTANCE.platformDispatcher.runGlobalAtFixedRate(1L, 1L, action)
        },
        startWorlds = {
            railWorlds.values.forEach { (it as? CoreWorld)?.physicsCoordinator?.start() }
            BukkitPlugin.INSTANCE.logger.info("Physics ticks started after server startup")
        },
        tick = ::tick
    )

    /** The first actual global tick starts physics after plugin composition completes. */
    fun start() = startup.prepare()

    private fun startRegisteredWorldPhysics(railWorld: World) = startup.startWorld {
        (railWorld as? CoreWorld)?.physicsCoordinator?.start()
    }

    fun stop() {
        startup.stop()
        stopping = true
        loadedWorlds.clearLookup()
        railWorlds.keys.toList().forEach { modelPresentationService?.removeWorld(it.uid, it) }
        unloadingWorlds.addAll(railWorlds.keys)
        worldTickRunners.close()
        partRenderClocks.clear()
        partRenderSamples.clear()
        trainFrameDispatcher.clear()
        platformCommitDispatcher.clear()
    }

    fun finishStopping() {
        loadedWorlds.clearLookup()
        val failures = mutableListOf<Exception>()
        snapshotWrites.forEach { (world, write) ->
            try {
                write.get()
            } catch (failure: Exception) {
                failures.add(failure)
                BukkitPlugin.INSTANCE.logger.log(
                    Level.SEVERE,
                    "Failed to finish snapshot write for world ${world.uid} (${world.name})",
                    failure
                )
            }
        }
        snapshotWriter.shutdown()
        check(snapshotWriter.awaitTermination(10L, TimeUnit.SECONDS)) { "World snapshot writer did not terminate" }
        if (failures.isNotEmpty()) throw IllegalStateException("World snapshot writes failed").also {
            failures.forEach(it::addSuppressed)
        }
    }

    fun latestServerTickMetrics(): JavaRailwaysServerTickMetrics? = latestServerTickMetricsSnapshot

    fun trainFrameDispatchMetrics(): TrainFrameDispatchMetrics {
        val metrics = trainFrameDispatcher.metrics()
        return TrainFrameDispatchMetrics(
            pendingTrains = metrics.activeKeys,
            coalescedFrames = metrics.coalescedValues,
            scheduledFrames = metrics.scheduledTasks
        )
    }

    @Synchronized
    fun recentServerTickMetrics(limit: Int = 20): List<JavaRailwaysServerTickMetrics> {
        if (limit <= 0) return emptyList()
        return recentServerTickMetrics.toList().takeLast(limit)
    }

    fun operationalTick(): Long = serverTickIndex

    fun registerRailWorld(bukkitWorld: BukkitWorld) {
        ThreadGuard.ensureMainThread("WorldManager.registerRailWorld")
        LiveWorldManagement.run {
            if (railWorlds.containsKey(bukkitWorld)) return@run
            unloadingWorlds.remove(bukkitWorld)

            val loadResult = WorldPersistence.loadSplitSnapshots(bukkitWorld)
            val split = when (val result = loadResult) {
                WorldPersistence.SplitSnapshotLoadResult.Missing -> null
                is WorldPersistence.SplitSnapshotLoadResult.Loaded -> result.snapshots
                is WorldPersistence.SplitSnapshotLoadResult.Corrupted -> {
                    BukkitPlugin.INSTANCE.logger.severe(
                        "Failed to load split JavaRailways snapshots for world '${bukkitWorld.name}' from " +
                            "${result.files.joinToString { it.path }}; features are disabled until fixed. " +
                            "${result.error::class.simpleName}: ${result.error.message.orEmpty()}"
                    )
                    return@run
                }
            }
            val splitInfrastructure = split?.infrastructure
            val splitRuntime = split?.runtime
            val railWorld = RailWorldFactory.createForBukkit(
                bukkitWorld = bukkitWorld,
                infrastructure = splitInfrastructure?.core(),
                runtime = splitRuntime?.core(),
                basicInfrastructure = splitInfrastructure?.basic(),
                simpleAtoInfrastructure = splitInfrastructure?.simpleAto(),
                basicRuntime = splitRuntime?.basic()
            )
            try {
                if (railWorld is CoreWorld) {
                    restoreSnapshotRevision(
                        railWorld,
                        (loadResult as? WorldPersistence.SplitSnapshotLoadResult.Loaded)?.highestRevision ?: 0L
                    )
                    infrastructureResourceService?.registerWorld(
                        bukkitWorld.uid,
                        { railWorld.topologyState },
                        registry = (railWorld as CoreWorld).infrastructureCoordination,
                        operatingDirections = railWorld.basicWorldData.operatingDirections::snapshot,
                        railSpeedLimits = railWorld.basicWorldData.railMetadata::speedLimits,
                        observeTopologyChanges = railWorld::registerTopologyChangeListener,
                        resourceExists = { resource ->
                        when (resource) {
                            is RailIntervalResource -> false // Physical intervals are validated by Core.
                            is StableInfrastructureResource ->
                                infrastructureResourceService.stableResourceExists(bukkitWorld.uid, resource)
                        }
                        }
                    )
                    infrastructureResourceService?.attachInfrastructureRuntime(
                        worldId = bukkitWorld.uid,
                        world = railWorld,
                        latestObservation = {
                            observationService?.current(bukkitWorld.uid, railWorld.latestWorldFrame())
                        },
                        stallWorld = { reason, failure ->
                            stallWorldRuntime(bukkitWorld, railWorld, reason, failure)
                        },
                        publishAdmissionUpdate = railWorld.simpleAtoWorldData.signalEpochRuntime::publishAdmissionUpdate
                    )
                    splitRuntime?.simpleAto()?.restore(railWorld)
                    val simpleAtoData = railWorld.simpleAtoWorldData
                    serverTickIndex = maxOf(
                        serverTickIndex,
                        simpleAtoData.dispatcher.saveCheckpoint().dispatcherTick
                    )
                    signalEpochSourceService?.registerWorld(
                        worldId = bukkitWorld.uid,
                        physicalWorld = railWorld,
                        runtime = simpleAtoData.signalEpochRuntime,
                        inspectionRuntime = simpleAtoData.inspectionRuntime,
                        routePlanningRuntime = simpleAtoData.routePlanningRestrictions,
                        activeRestrictionStreams = simpleAtoData.signalRestrictions::activeRestrictionStreams,
                        stallWorld = { reason, failure ->
                            stallWorldRuntime(bukkitWorld, railWorld, reason, failure)
                        }
                    )
                    stationCallResolverService?.registerWorld(bukkitWorld.uid, simpleAtoData.stationCallResolvers, railWorld.executionOwner)
                    val config = BukkitPlugin.INSTANCE.physicsConfig.overspeed
                    requireNotNull(simpleAtoData.operationalRuntime).attachSpeedSafety(
                        SpeedSafetyMonitor.Config(
                            derailEnabled = config.derailEnabled,
                            toleranceMetersPerSecond = config.toleranceKmH / 3.6,
                            graceTicks = config.graceTicks
                        )
                    )
                }
                val restoredTrains = split?.runtime?.core()?.trains.orEmpty()
                if (restoredTrains.isNotEmpty() && railWorld is CoreWorld) {
                    restoreBukkitTrainExtensions(railWorld, restoredTrains)
                    refreshTrainSpeedProfiles(railWorld)
                    markSkippedPersistedTrains(bukkitWorld, railWorld, restoredTrains)
                }
                loadedWorlds.publish(bukkitWorld, railWorld)
                modelPresentationService?.registerWorld(bukkitWorld.uid, bukkitWorld, railWorld, railWorld.renderChunkManager)
                startRegisteredWorldPhysics(railWorld)
            } catch (failure: Throwable) {
                fun attemptCleanup(action: () -> Unit) {
                    try {
                        action()
                    } catch (cleanupFailure: Throwable) {
                        if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
                    }
                }
                attemptCleanup { loadedWorlds.remove(bukkitWorld, railWorld) }
                worldTickRunners.retire(railWorld)
                val coreWorld = railWorld as? CoreWorld
                if (coreWorld != null) snapshotRevisions.remove(coreWorld)
                val cleanup: () -> Unit = {
                    if (coreWorld != null) {
                        attemptCleanup { coreWorld.physicsCoordinator.closeAndAwaitTermination() }
                        attemptCleanup { (coreWorld.tickManager as TickManagerImpl).closeAndAwaitTermination(coreWorld.runtimeId) }
                    }
                    attemptCleanup { modelPresentationService?.removeWorld(bukkitWorld.uid, bukkitWorld) }
                    attemptCleanup { stationCallResolverService?.removeWorld(bukkitWorld.uid) }
                    attemptCleanup { signalEpochSourceService?.removeWorld(bukkitWorld.uid) }
                    attemptCleanup { infrastructureResourceService?.removeWorld(bukkitWorld.uid) }
                    attemptCleanup { observationService?.removeWorld(bukkitWorld.uid) }
                    if (coreWorld != null) {
                        attemptCleanup { coreWorld.simpleAtoWorldDataOrNull?.operationalRuntime?.close() }
                        attemptCleanup { coreWorld.physicsCoordinator.close() }
                        attemptCleanup { coreWorld.infrastructureCoordination.close() }
                    }
                    if (coreWorld != null) {
                        attemptCleanup {
                            PlatformCleanup.awaitCompletion(RailVisualBootstrap.retireWorld(coreWorld))
                        }
                    }
                    attemptCleanup { railWorld.renderChunkManager.close() }
                    attemptCleanup { (railWorld.tickManager as? AutoCloseable)?.close() }
                }
                if (coreWorld == null) cleanup() else coreWorld.executionOwner.run(cleanup)
                throw failure
            }
        }
    }

    private fun stallWorldRuntime(
        bukkitWorld: BukkitWorld,
        coreWorld: CoreWorld,
        reason: String,
        failure: Exception
    ) {
        val observation = observationService?.latest(bukkitWorld.uid)?.orElse(null)
        coreWorld.simpleAtoWorldData.atoCoordinator.stall(
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

    fun refreshTrainSpeedProfiles(coreWorld: CoreWorld, onlyHeld: Boolean = false) {
        val plugin = BukkitPlugin.INSTANCE
        val worldId = coreWorld.bukkitWorldData.bukkitWorld.uid
        coreWorld.physicsCoordinator.withStructuralMutation {
            coreWorld.allTrains.filter { !onlyHeld || it.movementHeld }.forEach { train ->
                try {
                    val control = requireNotNull(train.rendererOrNull) {
                        "Train '${train.id}' has no configuration binding"
                    }.resolveControl()
                    val cars = train.cars.map { car ->
                        requireNotNull(car.carRendererOrNull?.resolveCarControl()) {
                            "Car '${car.id}' has no control configuration"
                        }
                    }
                    prepareTrainConfiguration(train, control, cars).install(train)
                    train.setMovementHeld(false)
                    plugin.worldIssues.resolve(worldId, "javarailways:train-configuration",
                        WorldIssueKind.TRAIN, train.id, "CONFIGURATION_UNAVAILABLE")
                } catch (failure: Exception) {
                    train.setMovementHeld(true)
                    plugin.worldIssues.report(worldId, "javarailways:train-configuration", WorldIssueInput(
                        WorldIssueKind.TRAIN, train.id, "CONFIGURATION_UNAVAILABLE",
                        "列车配置不可用，已暂停移动：${failure.message}"))
                    plugin.logger.log(java.util.logging.Level.SEVERE,
                        "Train '${train.id}' configuration is unavailable; holding movement", failure)
                }
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
                BukkitPlugin.INSTANCE.worldIssues.report(bukkitWorld.uid, "javarailways:train-restore", WorldIssueInput(
                    WorldIssueKind.TRAIN, savedTrain.id, "RESTORE_FAILED", "列车存档无法恢复，已标记原位置；详情见服务器日志"))
                AbnormalTrainMarker.markSavedTrain(coreWorld, bukkitWorld, savedTrain, "load failed")
            }
    }

    fun unregisterRailWorld(bukkitWorld: BukkitWorld, saveSnapshot: Boolean = true) {
        unregisterRailWorld(
            bukkitWorld = bukkitWorld,
            captureAndSave = { coreWorld ->
                // unregister restores manual control at a live frame boundary before entering
                // terminal physics shutdown. Only a fully stopped coordinator may be captured.
                coreWorld.physicsCoordinator.stopAndAwait()
                if (saveSnapshot) {
                    val ticket = coreWorld.physicsCoordinator.captureCurrentTicket()
                    ticket.await()
                    val snapshots = captureSnapshots(coreWorld, ticket.epoch)
                    snapshotWrites.remove(bukkitWorld)?.let { write ->
                        runCatching { write.get() }.onFailure { failure ->
                            BukkitPlugin.INSTANCE.logger.log(Level.WARNING, "Previous snapshot write failed", failure)
                        }
                    }
                    pendingPeriodicSnapshots.remove(bukkitWorld)
                    snapshotWriter.submit {
                        WorldPersistence.saveSplitSnapshots(bukkitWorld.uid, snapshots)
                    }.get()
                } else {
                    pendingPeriodicSnapshots.remove(bukkitWorld)
                    BukkitPlugin.INSTANCE.logger.severe(
                        "Skipped final snapshot for ${bukkitWorld.uid}: runtime producers did not stop cleanly; " +
                            "preserving the existing snapshots and backups"
                    )
                }
            },
            clearAbnormalMarkers = AbnormalTrainMarker::clearWorld,
            closeRender = { world -> world.renderChunkManager.close() },
            closeTick = { world -> (world.tickManager as? AutoCloseable)?.close() }
        )
    }

    /** Test seam for the unload failure matrix; production ownership remains in the public overload. */
    internal fun unregisterRailWorld(
        bukkitWorld: BukkitWorld,
        captureAndSave: (CoreWorld) -> Unit,
        clearAbnormalMarkers: (BukkitWorld) -> Unit,
        closeRender: (World) -> Unit,
        closeModelOperations: (World) -> Unit = { it.bukkitWorldData.closeModelOperations() },
        closeTick: (World) -> Unit
    ) {
        ThreadGuard.ensureMainThread("WorldManager.unregisterRailWorld")
        LiveWorldManagement.run {
            unloadingWorlds.add(bukkitWorld)
            loadedWorlds.withdraw(bukkitWorld)

            val ownedWorld = railWorlds[bukkitWorld]
            if (ownedWorld != null) worldTickRunners.retire(ownedWorld)
            val unload: () -> Unit = {
                var firstFailure: Throwable? = null
                fun attempt(action: () -> Unit) {
                    try {
                        action()
                    } catch (failure: Throwable) {
                        val primary = firstFailure
                        if (primary == null) {
                            firstFailure = failure
                        } else if (failure !== primary) {
                            primary.addSuppressed(failure)
                        }
                    }
                }

                attempt { modelPresentationService?.removeWorld(bukkitWorld.uid, bukkitWorld) }
                if (ownedWorld != null) {
                    val coreWorld = ownedWorld as? CoreWorld
                    if (coreWorld != null) {
                        attempt {
                            coreWorld.physicsCoordinator.processBetweenFrames {
                                // Retire this world's sessions before final capture, without draining
                                // unrelated player requests once unloading has started.
                                releaseManualControl(coreWorld)
                            }
                        }
                        attempt { retireManualControl(coreWorld) }
                        attempt { captureAndSave(coreWorld) }
                        attempt { coreWorld.physicsCoordinator.closeAndAwaitTermination() }
                        attempt { (coreWorld.tickManager as TickManagerImpl).closeAndAwaitTermination(coreWorld.runtimeId) }
                        attempt { coreWorld.simpleAtoWorldDataOrNull?.operationalRuntime?.close() }
                    }
                    attempt { infrastructureResourceService?.removeWorld(bukkitWorld.uid) }
                    attempt { stationCallResolverService?.removeWorld(bukkitWorld.uid) }
                    attempt { signalEpochSourceService?.removeWorld(bukkitWorld.uid) }
                    attempt { observationService?.removeWorld(bukkitWorld.uid) }
                    attempt { closeModelOperations(ownedWorld) }
                    ownedWorld.allTrains.flatMap { it.cars }.forEach { car ->
                        attempt { car.carRendererOrNull?.closeBehavior() }
                    }
                    attempt { coreWorld?.physicsCoordinator?.close() }
                    if (coreWorld != null) {
                        attempt {
                            PlatformCleanup.awaitCompletion(RailVisualBootstrap.retireWorld(coreWorld))
                        }
                    }
                    attempt { closeRender(ownedWorld) }
                    attempt { closeTick(ownedWorld) }
                    attempt { coreWorld?.infrastructureCoordination?.close() }
                }
                attempt { BukkitPlugin.instanceOrNull()?.worldIssues?.clearWorld(bukkitWorld.uid) }
                attempt { clearAbnormalMarkers(bukkitWorld) }
                attempt {
                    if (ownedWorld != null) {
                        railWorlds.remove(bukkitWorld, ownedWorld)
                        if (ownedWorld is CoreWorld) {
                            snapshotRevisions.remove(ownedWorld)
                            partRenderClocks.remove(ownedWorld)
                            partRenderSamples.remove(ownedWorld)
                        }
                    }
                }
                firstFailure?.let { throw it }
            }
            try {
                if (ownedWorld == null) unload() else BukkitThreadGuardBridge.runInSimulationContext(ownedWorld, unload)
            } finally {
                forgetWorldTickMetrics(bukkitWorld)
            }
        }
    }

    @EventHandler
    fun onWorldLoad(event: WorldLoadEvent) {
        withSimulationContext { registerRailWorld(event.world) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onWorldUnload(event: WorldUnloadEvent) {
        PlatformCleanup.runAndAwait { withSimulationContext { unregisterRailWorld(event.world) } }
    }

    private fun tick() {
        if (stopping || !startup.isRunning || RailwaySimulationHalt.isHalted) return
        serverTickIndex += 1L
        val wake = WorldTickWake(serverTickIndex, System.currentTimeMillis())
        railWorlds.forEach { (bukkitWorld, railWorld) ->
            if (bukkitWorld !in unloadingWorlds) {
                worldTickRunners.offer(railWorld, wake) {
                    val snapshotInterval = WorldSnapshotInterval(PERIODIC_SNAPSHOT_TICKS)
                    WorldTickRunner(bukkitWorld.uid.toString(), { next ->
                        BukkitThreadGuardBridge.runInSimulationContext(railWorld) {
                            if (!stopping && !RailwaySimulationHalt.isHalted && bukkitWorld !in unloadingWorlds && railWorlds[bukkitWorld] === railWorld) {
                                (railWorld as? CoreWorld)?.physicsCoordinator?.wakeFromMainThread()
                                railWorld.tickManager.preTick(next.millis)
                                tickWorld(bukkitWorld, railWorld, next.index, snapshotInterval)
                            }
                        }
                    }, { failure ->
                        val coordinator = (railWorld as? CoreWorld)?.physicsCoordinator
                        if (stopping || bukkitWorld in unloadingWorlds ||
                            (!RailwaySimulationHalt.isHalted && (coordinator?.status ==
                                work.art1st.javarailways.core.physics.tick.PhysicsCoordinatorStatus.STALLED ||
                                isPhysicsOverloadFailure(failure)))) {
                            // Normal retirement and the explicit per-world overload budget are
                            // not breaches of shared Core state.
                            BukkitPlugin.INSTANCE.logger.log(Level.SEVERE,
                                "World tick stopped for ${bukkitWorld.uid} (${bukkitWorld.name})", failure)
                        } else {
                            RailwaySimulationHalt.halt("World tick failed for ${bukkitWorld.uid} (${bukkitWorld.name})", failure)
                        }
                        // Only join the generic worker batch. Failed owner stages must not drain
                        // queued mutations or replace the old barrier on a subsequent wake.
                        (railWorld.tickManager as TickManagerImpl).finishPendingAsyncTick()
                    })
                }
            }
        }
    }

    private fun tickWorld(world: BukkitWorld, railWorld: World, tickIndex: Long, snapshots: WorldSnapshotInterval) {
        ThreadGuard.ensureMainThread("WorldManager.tickWorld")
        val phaseBreakdown = linkedMapOf<String, Long>()
        var computeNanos = 0L
        var blockedWaitAsyncNanos = 0L

        var publishedPhysicsEpoch = 0L
        val boundaryStartedNanos = System.nanoTime()
        val processOwnerTick: (PhysicsTicket?) -> Unit = { physicsTicket ->
            (railWorld as? CoreWorld)?.let { coreWorld ->
                val completedTicket = checkNotNull(physicsTicket)
                val physicsWaitNanos = System.nanoTime() - boundaryStartedNanos
                publishedPhysicsEpoch = completedTicket.epoch
                phaseBreakdown["world:${world.name}:physics_wait"] = physicsWaitNanos
                blockedWaitAsyncNanos += physicsWaitNanos

                val operationsNanos = measureNanoTime {
                    coreWorld.physicsCoordinator.drainBetweenFramesOperations()
                    val issues = BukkitPlugin.INSTANCE.worldIssues
                    issues.snapshot(world.uid).filter {
                        it.kind == WorldIssueKind.TRAIN && it.source != "javarailways:train-restore" &&
                            coreWorld.getTrain(it.objectId) == null
                    }.forEach { issue ->
                        issues.resolve(world.uid, issue.source, issue.kind, issue.objectId, issue.code)
                    }
                }
                phaseBreakdown["world:${world.name}:between_frames_operations"] = operationsNanos
                computeNanos += operationsNanos

                requireNotNull(coreWorld.simpleAtoWorldData.operationalRuntime).afterPublication(
                    completedTicket.epoch,
                    publishObservation = {
                        val nanos = measureNanoTime {
                            val observation = observationService?.publish(
                                world.uid, coreWorld.latestWorldFrame(), completedTicket.epoch)
                            if (observation != null) infrastructureResourceService?.consumeObservation(world.uid, observation)
                        }
                        phaseBreakdown["world:${world.name}:observation_publish"] = nanos
                        computeNanos += nanos
                    },
                    operationalTick = tickIndex
                )
                // Operational display actions and postTick effects must finish before this capture.
                // Observation, dispatcher and physics publication ordering above remains unchanged.
                val renderNanos = measureNanoTime { dispatchPublishedTrainFrames(world, coreWorld, tickIndex) }
                phaseBreakdown["world:${world.name}:train_visual_frame_consume"] = renderNanos
                computeNanos += renderNanos
            }
        }

        val coordinator = (railWorld as? CoreWorld)?.physicsCoordinator
        if (coordinator == null) {
            railWorld.simpleAtoWorldData.dispatcher.tick(railWorld, tickIndex)
            railWorld.tickManager.postTick()
        } else {
            // Keep fallback physics out through dispatcher and generic after-commit work.
            coordinator.processBetweenFrames { processOwnerTick(it) }
        }

        RailwaySimulationHalt.ensureRunning()
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

        (railWorld as? CoreWorld)?.let { coreWorld ->
            snapshots.captureIfDue(tickIndex) {
                enqueuePeriodicSnapshot(world, captureSnapshots(coreWorld, publishedPhysicsEpoch))
            }
        }

        rememberServerTickMetrics(world,
            JavaRailwaysServerTickMetrics(
                tickIndex = tickIndex,
                computeNanos = computeNanos,
                blockedWaitAsyncNanos = blockedWaitAsyncNanos,
                totalImpactNanos = computeNanos + blockedWaitAsyncNanos,
                phaseBreakdownNanos = phaseBreakdown.toMap()
            )
        )
    }

    /** Keeps at most one in-flight and one latest pending frame per train stream. */
    private fun dispatchPublishedTrainFrames(bukkitWorld: BukkitWorld, coreWorld: CoreWorld, tickIndex: Long) {
        val data = coreWorld.simpleAtoWorldData
        val sampleInputs = partRenderClocks.getOrPut(coreWorld, ::PartRenderSamplingClock)
            .sample(tickIndex, data.dispatcher.unitDisplayRevision(coreWorld))
        val samples = partRenderSamples.getOrPut(coreWorld, ::TrainPartRenderSampleCache)
        if (sampleInputs) samples.clear()
        val displayLines = if (sampleInputs)
            work.art1st.javarailways.bukkit.extension.train.captureDisplayLineIndex(data.lines.snapshot().lines)
        else kotlinx.serialization.json.JsonObject(emptyMap())
        val operationalByCar = if (sampleInputs) {
            val byCar = linkedMapOf<String, OperationalPartRenderProjection>()
            val stationMetadata: (String) -> kotlinx.serialization.json.JsonObject = { stationId ->
                data.stations[StationId(stationId)]?.metadata ?: kotlinx.serialization.json.JsonObject(emptyMap())
            }
            data.dispatcher.captureUnitDisplays(coreWorld).forEach { context ->
                val projection = projectOperationalPartRender(context, stationMetadata)
                context.carIds.forEach { carId ->
                    check(byCar.put(carId, projection) == null) { "Car '$carId' belongs to overlapping display units" }
                }
            }
            byCar
        } else emptyMap()
        val passengers = BukkitPlugin.INSTANCE.passengerMovement?.capture(bukkitWorld).orEmpty()
        consumeTrainRenderFrames(coreWorld, sampleInputs,
            needsClock = { it.carRendererOrNull?.hasDynamicExtensionModel == true }) { train, trainFrame, indices ->
            val trainType = train.rendererOrNull?.modelPackageName()
            // Capture the same operating-end basis used by TrainExtension door execution.
            val controlTail = if (sampleInputs)
                BukkitPlugin.INSTANCE.trainControlManager.operatingControlEndFor(train) == DriverControlEnd.TAIL
            else null
            val cars = indices.map { index ->
                CarRenderFrame(
                    train.cars[index], trainFrame.cars[index],
                    if (sampleInputs) samples.capture(
                        trainFrame, index, trainType, tickIndex,
                        operationalByCar[trainFrame.cars[index].carId] ?: OperationalPartRenderProjection.EMPTY,
                        train.publicationStreamId, displayLines,
                        operatingTail = train.behaviorRelativeTail(train.cars[index], checkNotNull(controlTail))
                    ) else samples.latest(trainFrame, index, train.publicationStreamId)
                )
            }
            trainFrameDispatcher.offer(train.publicationStreamId, PendingTrainFrame(
                bukkitWorld, train.publicationStreamId, trainFrame, cars,
                passengers[train.publicationStreamId].orEmpty()
            ))
        }
    }

    private fun scheduleTrainFrame(pending: PendingTrainFrame, complete: () -> Unit) {
        if (stopping || pending.world in unloadingWorlds) {
            complete()
            return
        }
        val plugin = BukkitPlugin.INSTANCE
        dispatchTrainFrameChunks(
            pending.cars,
            locationOf = { (_, frame) ->
                Location(pending.world, frame.state.positionX, frame.state.positionY, frame.state.positionZ)
            },
            schedule = plugin.platformDispatcher::runAt,
        ) { cars ->
            dispatchTrainPresentation(pending, cars, { plugin.trainPacketBundles.frame(it) },
                apply = { item -> item.car.carRendererOrNull?.applyPublishedFrame(
                    item.frame, pending.streamId, pending.frame, item.partRenderSnapshot) },
                present = { rider, accepted, flushed -> plugin.passengerMovement?.render(rider, accepted, flushed) },
                isCurrent = { !stopping && pending.world !in unloadingWorlds },
                failed = { car, failure -> plugin.logger.log(Level.SEVERE,
                    "Failed to render car ${car.id} at revision ${pending.frame.revision}", failure) })
        }.whenComplete { _, failure ->
            try {
                if (failure != null) plugin.logger.log(
                    Level.SEVERE, "Failed to dispatch train render frame ${pending.streamId}/${pending.frame.revision}", failure
                )
            } finally {
                complete()
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

    internal fun restoreSnapshotRevision(coreWorld: CoreWorld, highestRevision: Long) {
        require(highestRevision >= 0L)
        check(snapshotRevisions.putIfAbsent(coreWorld, highestRevision) == null)
    }

    internal fun nextSnapshotRevision(coreWorld: CoreWorld): Long =
        snapshotRevisions.compute(coreWorld) { _, previous ->
            Math.addExact(checkNotNull(previous) { "World snapshot revision was not initialized" }, 1L)
        }!!

    private fun captureSnapshots(coreWorld: CoreWorld, physicsEpoch: Long): WorldSnapshotCapture {
        val revision = nextSnapshotRevision(coreWorld)
        val coreInfrastructure = coreWorld.saveInfrastructure(revision)
        val basicInfrastructure = BasicInfrastructureSnapshotV2.capture(
            coreWorld,
            revision,
            coreWorld.simpleAtoWorldData.destinations.all().mapTo(HashSet()) { it.id }
        )
        val simpleAtoInfrastructure = SimpleATOInfrastructureSnapshotV1.capture(coreWorld, revision)
        val coreRuntime = coreWorld.saveRuntime(revision, physicsEpoch.coerceAtLeast(0L))
        val basicRuntime = BasicRuntimeSnapshotV3.capture(
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
            simpleAtoInfrastructure,
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

    private data class PendingPlatformCommit(
        val synchronizable: Synchronizable,
        val location: Location,
        val action: () -> Unit
    )


    @Synchronized
    internal fun rememberServerTickMetrics(world: BukkitWorld, snapshot: JavaRailwaysServerTickMetrics) {
        worldTickMetrics[world] = snapshot
        val aggregate = checkNotNull(aggregateWorldTickMetrics())
        latestServerTickMetricsSnapshot = aggregate
        recentServerTickMetrics.addLast(aggregate)
        while (recentServerTickMetrics.size > 200) recentServerTickMetrics.removeFirst()
    }

    @Synchronized
    private fun forgetWorldTickMetrics(world: BukkitWorld) {
        worldTickMetrics.remove(world)
        latestServerTickMetricsSnapshot = aggregateWorldTickMetrics()
    }

    private fun aggregateWorldTickMetrics(): JavaRailwaysServerTickMetrics? {
        val values = worldTickMetrics.values.toList()
        if (values.isEmpty()) return null
        return JavaRailwaysServerTickMetrics(
            tickIndex = values.maxOf { it.tickIndex },
            computeNanos = values.sumOf { it.computeNanos },
            blockedWaitAsyncNanos = values.sumOf { it.blockedWaitAsyncNanos },
            totalImpactNanos = values.sumOf { it.totalImpactNanos },
            phaseBreakdownNanos = values.flatMap { it.phaseBreakdownNanos.entries }.associate { it.toPair() }
        )
    }

}

/** Maintained only at world load/unload; the existing Bukkit-key map remains the lifecycle owner. */
internal class LoadedRailWorldRegistry(private val railWorlds: MutableMap<BukkitWorld, World>) {
    private val byId = ConcurrentHashMap<UUID, World>()

    init {
        railWorlds.forEach { (bukkitWorld, railWorld) -> byId[bukkitWorld.uid] = railWorld }
    }

    fun find(worldId: UUID): World? = byId[worldId]

    fun publish(bukkitWorld: BukkitWorld, railWorld: World) {
        railWorlds[bukkitWorld] = railWorld
        byId[bukkitWorld.uid] = railWorld
    }

    fun withdraw(bukkitWorld: BukkitWorld) {
        railWorlds[bukkitWorld]?.let { byId.remove(bukkitWorld.uid, it) }
    }

    fun remove(bukkitWorld: BukkitWorld, railWorld: World) {
        railWorlds.remove(bukkitWorld, railWorld)
        byId.remove(bukkitWorld.uid, railWorld)
    }

    fun clearLookup() {
        byId.clear()
    }
}

/** Drain refresh notifications at the world's frame boundary, then capture each current complete frame. */
internal fun consumePendingTrainFrames(
    coreWorld: CoreWorld,
    consume: (CoreTrain, TrainFrameSnapshot) -> Unit
) {
    coreWorld.drainPublishedTrainFrames().forEach { queuedFrame ->
        val train = coreWorld.getTrain(queuedFrame.trainId) ?: return@forEach
        val latestFrame = train.publishedFrame
        consume(train, latestFrame)
    }
}

/** Preserve Core's stationary mailbox suppression; only live extension models need a 2Hz clock. */
internal fun consumeTrainRenderFrames(
    coreWorld: CoreWorld,
    refreshStationary: Boolean,
    needsClock: (CoreCar) -> Boolean,
    consume: (CoreTrain, TrainFrameSnapshot, List<Int>) -> Unit
) {
    val dispatched = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<CoreCar, Boolean>())
    consumePendingTrainFrames(coreWorld) { train, frame ->
        val indices = frame.cars.indices.toList()
        indices.forEach { dispatched.add(train.cars[it]) }
        consume(train, frame, indices)
    }
    if (!refreshStationary) return
    coreWorld.allTrains.forEach { train ->
        val frame = train.publishedFrame
        val indices = frame.cars.indices.filter { index ->
            val car = train.cars[index]
            car !in dispatched && needsClock(car)
        }
        if (indices.isNotEmpty()) consume(train, frame, indices)
    }
}

/** Owns the first-global-tick barrier shared by initial worlds and later world loads. */
internal class ServerPhysicsStartup(
    private val scheduleTicks: (() -> Unit) -> ScheduledTask,
    private val startWorlds: () -> Unit,
    private val tick: () -> Unit
) {
    private val lock = Any()
    private var prepared = false
    private var stopped = false
    private var task: ScheduledTask? = null
    @Volatile
    var isRunning = false
        private set

    fun prepare() = synchronized(lock) {
        check(!prepared && !stopped) { "WorldManager is already started or stopped" }
        prepared = true
        // Paper and Folia drive the global scheduler after startup callbacks complete.
        // Scheduling also works on hot enable, without another startup event or a native ready flag.
        task = scheduleTicks {
            if (!isRunning) LiveWorldManagement.run {
                synchronized(lock) {
                    if (!stopped && !isRunning) {
                        startWorlds()
                        isRunning = true
                    }
                }
            }
            if (isRunning) tick()
        }
    }

    fun startWorld(start: () -> Unit) = synchronized(lock) {
        if (isRunning && !stopped) start()
    }

    fun stop() = synchronized(lock) {
        stopped = true
        isRunning = false
        task?.cancel()
        task = null
    }
}

/** An old failed epoch can reach the owner after a manual reset has already resumed physics. */
internal fun isPhysicsOverloadFailure(failure: Throwable): Boolean =
    generateSequence(failure) { it.cause }.any {
        it is work.art1st.javarailways.core.physics.tick.PhysicsOverloadException
    }

/** A train frame spans one world. Each chunk runs on its owner, and all chunks settle before the next frame. */
internal fun <T> dispatchTrainFrameChunks(
    cars: List<T>,
    locationOf: (T) -> Location,
    schedule: (Location, () -> Unit) -> Unit,
    render: (List<T>) -> Unit
): CompletableFuture<Void> {
    val chunks = cars.map { it to locationOf(it) }.groupBy { (_, location) ->
        (location.blockX shr 4) to (location.blockZ shr 4)
    }
    val completions = chunks.values.map { targets ->
        val completion = CompletableFuture<Void>()
        try {
            schedule(targets.first().second) {
                try {
                    render(targets.map { it.first })
                    completion.complete(null)
                } catch (failure: Throwable) {
                    completion.completeExceptionally(failure)
                }
            }
        } catch (failure: Throwable) {
            completion.completeExceptionally(failure)
        }
        completion
    }
    return CompletableFuture.allOf(*completions.toTypedArray())
}

/** Normal carriage rendering carries a once-captured set of player-relative positions. */
internal data class PendingTrainFrame(
    val world: BukkitWorld,
    val streamId: UUID,
    val frame: TrainFrameSnapshot,
    val cars: List<CarRenderFrame>,
    val passengers: List<PassengerMovementManager.Presentation> = emptyList()
)

internal data class CarRenderFrame(
    val car: CoreCar,
    val frame: CarFrameSnapshot,
    val partRenderSnapshot: TrainPartRenderSnapshot?
)

/** Uses the accepted car pose and completes the carrier batch only after the outer bundle enqueue. */
internal fun dispatchTrainPresentation(
    pending: PendingTrainFrame,
    cars: List<CarRenderFrame>,
    bundle: (() -> Unit) -> Unit,
    apply: (CarRenderFrame) -> TrainFrameSnapshot?,
    present: (PassengerMovementManager.Presentation, TrainFrameSnapshot, CompletableFuture<Void>) -> Unit,
    isCurrent: () -> Boolean,
    failed: (CoreCar, Exception) -> Unit
) {
    val flushed = CompletableFuture<Void>()
    try {
        bundle {
            cars.forEach { item ->
                if (!isCurrent() || !item.car.isOwnedByPublicationStream(pending.streamId)) return@forEach
                try {
                    val accepted = apply(item) ?: return@forEach
                    pending.passengers.filter { it.state.registration.car === item.car }.forEach {
                        present(it, accepted, flushed)
                    }
                } catch (failure: Exception) { failed(item.car, failure) }
            }
        }
        flushed.complete(null)
    } catch (failure: Throwable) {
        flushed.completeExceptionally(failure)
        throw failure
    }
}
