package work.art1st.javarailways.simpleato.timetable.simulation

import work.art1st.javarailways.basic.BasicPlugin
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.simpleato.api.ato.AtoStallReport
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureResource
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureDependencyRequest
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.infrastructure.RailIntervalResource
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.route.RouteAdmissionHandle
import work.art1st.javarailways.basic.api.route.RouteAdmissionResult
import work.art1st.javarailways.basic.api.route.RouteGrantId
import work.art1st.javarailways.basic.api.route.RouteGrantReversalResult
import work.art1st.javarailways.basic.api.route.RouteResourceAdmissionService
import work.art1st.javarailways.basic.api.route.RouteResourceIntent
import work.art1st.javarailways.basic.infrastructure.InfrastructureCoordinationRegistry
import work.art1st.javarailways.basic.infrastructure.RouteResourceAdmissionRuntime
import work.art1st.javarailways.basic.persistence.BasicInfrastructureSnapshotV2
import work.art1st.javarailways.simpleato.persistence.SimpleATOInfrastructureSnapshotV1
import work.art1st.javarailways.simpleato.timetable.Timetable
import work.art1st.javarailways.simpleato.api.timetable.TimetableExtensionRegistry
import work.art1st.javarailways.simpleato.SimpleATOPlugin
import work.art1st.javarailways.simpleato.SimpleATOWorldData
import work.art1st.javarailways.simpleato.api.ato.AtoWorldCoordinator
import work.art1st.javarailways.simpleato.restriction.TrainRestrictionSnapshotProvider
import work.art1st.javarailways.simpleato.restriction.TrainRestrictionSnapshotStore
import work.art1st.javarailways.simpleato.inspection.InspectionRuntime
import work.art1st.javarailways.simpleato.signal.SignalEpochRuntime
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.timetable.TimetableDispatcher
import work.art1st.javarailways.simpleato.timetable.InfrastructureDependencyService
import work.art1st.javarailways.simpleato.timetable.TimetableEpochStartResult
import work.art1st.javarailways.simpleato.timetable.TimetableRouteAdmissionContext
import work.art1st.javarailways.simpleato.timetable.TimetableRouteAdmissionContextProvider
import work.art1st.javarailways.simpleato.timetable.TimetableServiceRunSnapshot
import work.art1st.javarailways.simpleato.timetable.TimetableServiceRunState
import work.art1st.javarailways.core.di.NoOpWorldParentContext
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.util.IsolatedMainThreadMarker
import work.art1st.javarailways.core.util.TickManagerImpl
import java.util.concurrent.Executors

/** Request for one deterministic cycle in a world containing no live runtime state. */
data class IsolatedTimetableSimulationRequest(
    val timetable: Timetable,
    val delayThresholdTicks: Long = 1_200L,
    val maximumSimulationTicks: Long = timetable.periodTicks
) {
    init {
        require(delayThresholdTicks > 0L)
        require(maximumSimulationTicks > 0L && maximumSimulationTicks <= timetable.periodTicks)
    }
}

sealed interface IsolatedTimetableSimulationResult {
    val simulatedTicks: Long
    val runs: List<TimetableServiceRunSnapshot>

    data class Completed(
        override val simulatedTicks: Long,
        override val runs: List<TimetableServiceRunSnapshot>
    ) : IsolatedTimetableSimulationResult

    data class DelayExceeded(
        override val simulatedTicks: Long,
        val thresholdTicks: Long,
        val observedDelayTicks: Long,
        val delayedRun: TimetableServiceRunSnapshot,
        override val runs: List<TimetableServiceRunSnapshot>
    ) : IsolatedTimetableSimulationResult

    data class Failed(
        override val simulatedTicks: Long,
        val reason: String,
        val failedRun: TimetableServiceRunSnapshot? = null,
        override val runs: List<TimetableServiceRunSnapshot> = emptyList()
    ) : IsolatedTimetableSimulationResult

    data class PeriodEnded(
        override val simulatedTicks: Long,
        override val runs: List<TimetableServiceRunSnapshot>
    ) : IsolatedTimetableSimulationResult
}

/**
 * Owns a static Core/Basic world copy and isolated worker pools. Runtime trains must be created by
 * [seed] from timetable profile assignments; no live-world train, reservation, or dispatcher state
 * is restored.
 */
class IsolatedTimetableSimulationWorld private constructor(
    val world: CoreWorld,
    private val tickManager: TickManagerImpl,
    private val physicsCoordinator: PhysicsCoordinator,
    private val routeAdmissionHost: IsolatedRouteAdmissionHost
) : AutoCloseable {
    private var logicalMillis = 0L
    private var hasSimulated = false

    fun simulate(request: IsolatedTimetableSimulationRequest): IsolatedTimetableSimulationResult =
        IsolatedMainThreadMarker.run {
            check(!hasSimulated) { "an isolated simulation world is single-use" }
            hasSimulated = true
            try {
                simulateOnIsolatedThread(request)
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                IsolatedTimetableSimulationResult.Failed(
                    logicalMillis / LOGICAL_TICK_MILLIS,
                    "simulation cancelled",
                    runs = cycleZeroRuns(request.timetable.id)
                )
            } catch (failure: Exception) {
                IsolatedTimetableSimulationResult.Failed(
                    logicalMillis / LOGICAL_TICK_MILLIS,
                    failure.message ?: failure::class.java.simpleName,
                    runs = cycleZeroRuns(request.timetable.id)
                )
            }
        }

    private fun simulateOnIsolatedThread(
        request: IsolatedTimetableSimulationRequest
    ): IsolatedTimetableSimulationResult {
        val dispatcher = world.simpleAtoWorldData.dispatcher
        world.simpleAtoWorldData.timetables.add(request.timetable)
        when (val started = dispatcher.beginTimetable(world, request.timetable.id)) {
            is TimetableEpochStartResult.Failure -> return IsolatedTimetableSimulationResult.Failed(
                0L, started.reason
            )
            is TimetableEpochStartResult.Started -> Unit
        }
        if (!dispatcher.scheduleInitialCycle(request.timetable.id)) {
            return IsolatedTimetableSimulationResult.Failed(0L, "could not schedule initial timetable cycle")
        }

        for (tick in 1L..request.maximumSimulationTicks) {
            if (Thread.currentThread().isInterrupted) {
                return IsolatedTimetableSimulationResult.Failed(
                    tick - 1L,
                    "simulation cancelled",
                    runs = cycleZeroRuns(request.timetable.id)
                )
            }
            step()
            val runs = cycleZeroRuns(request.timetable.id)
            world.simpleAtoWorldData.atoCoordinator.lastStall?.let { stall ->
                return IsolatedTimetableSimulationResult.Failed(
                    tick,
                    "${stall.errorType}: ${stall.message.orEmpty()}",
                    runs = runs
                )
            }
            runs.firstOrNull { it.state == TimetableServiceRunState.FAILED }?.let { failed ->
                return IsolatedTimetableSimulationResult.Failed(
                    tick,
                    failed.message ?: "service failed",
                    failed,
                    runs
                )
            }
            runs.map { run -> run to run.observedDelayTicks() }
                .maxByOrNull { it.second }
                ?.takeIf { it.second >= request.delayThresholdTicks }
                ?.let { (delayed, observedDelay) ->
                    return IsolatedTimetableSimulationResult.DelayExceeded(
                        tick,
                        request.delayThresholdTicks,
                        observedDelay,
                        delayed,
                        runs
                    )
                }
            if (runs.size == request.timetable.services.size && runs.all { it.isSuccessfulTerminal() }) {
                return IsolatedTimetableSimulationResult.Completed(tick, runs)
            }
        }
        return IsolatedTimetableSimulationResult.PeriodEnded(
            request.maximumSimulationTicks,
            cycleZeroRuns(request.timetable.id)
        )
    }

    private fun step() {
        logicalMillis = Math.addExact(logicalMillis, LOGICAL_TICK_MILLIS)
        tickManager.preTick(logicalMillis)
        physicsCoordinator.stepSimulation(LOGICAL_TICK_MILLIS.toDouble()).await()
        routeAdmissionHost.publish(
            RailwayObservation.fromCore(
                world.simpleAtoWorldData.atoCoordinator.worldId,
                world.latestWorldFrame()
            )
        )
        world.simpleAtoWorldData.dispatcher.tick(world)
        tickManager.postTick()
        tickManager.drainPendingCommits().forEach { it.commit() }
    }

    private fun cycleZeroRuns(timetableId: String): List<TimetableServiceRunSnapshot> =
        world.simpleAtoWorldData.dispatcher.statuses()
            .filter { it.id.timetableId == timetableId && it.cycleIndex == 0L }
            .sortedBy { it.id.serviceId }

    override fun close() {
        routeAdmissionHost.close()
        physicsCoordinator.close()
        tickManager.close()
    }

    companion object {
        private const val LOGICAL_TICK_MILLIS = 50L

        fun create(
            coreInfrastructure: CoreWorld.SavedInfrastructureV2,
            basicInfrastructure: BasicInfrastructureSnapshotV2,
            simpleAtoInfrastructure: SimpleATOInfrastructureSnapshotV1,
            workerThreads: Int,
            basicDataFactory: (CoreWorld, (Set<java.util.UUID>) -> Unit) -> BasicWorldData,
            timetableExtensions: TimetableExtensionRegistry = TimetableExtensionRegistry(),
            dispatcherFactory: (
                CoreWorld,
                TimetableRouteAdmissionContextProvider,
                TrainRestrictionSnapshotProvider,
                InspectionRuntime,
                InfrastructureDependencyService
            ) -> TimetableDispatcher,
            seed: (CoreWorld) -> Unit
        ): IsolatedTimetableSimulationWorld {
            require(workerThreads > 0)
            require(coreInfrastructure.infrastructureRevision == basicInfrastructure.infrastructureRevision)
            require(coreInfrastructure.infrastructureRevision == simpleAtoInfrastructure.infrastructureRevision)
            val tickManager = TickManagerImpl(
                Executors.newScheduledThreadPool(workerThreads) { runnable ->
                    Thread(runnable, "JavaRailways-SimulationTick").apply { isDaemon = true }
                },
                0L
            )
            val physics = PhysicsCoordinator(
                workerThreads = workerThreads,
                epochOverloadThresholdMillis = 60_000L,
                overloadWindowMillis = 60_000L,
                overloadsBeforeStall = Int.MAX_VALUE,
                automaticFallback = false,
                publishCollisionEvents = false
            )
            var routeAdmissionHost: IsolatedRouteAdmissionHost? = null
            try {
                val world = IsolatedMainThreadMarker.run {
                    CoreWorld.load(
                        coreInfrastructure,
                        runtime = null,
                        dependency = CoreWorld.SnapshotDependency(
                            tickManager,
                            NoOpWorldParentContext,
                            physics
                        )
                    ).also { isolatedWorld ->
                        val worldId = "isolated"
                        val signalRestrictions = TrainRestrictionSnapshotStore(worldId)
                        val inspectionRuntime = InspectionRuntime(signalRestrictions) { _, _, _ -> }
                        val signalEpochRuntime = SignalEpochRuntime(
                            worldId = worldId,
                            publisher = signalRestrictions,
                            completeEpoch = signalRestrictions::completeEpoch,
                            invalidateInspectionDeclarations = inspectionRuntime::markDirty
                        )
                        val basicData = basicDataFactory(isolatedWorld, signalEpochRuntime::publishOperatingDirectionChanges)
                        isolatedWorld.registerExtensionData(BasicPlugin.INSTANCE, basicData)
                        basicInfrastructure.restore(isolatedWorld)
                        val atoCoordinator = AtoWorldCoordinator(
                            worldId = worldId,
                            safetyInterlock = basicData.safetyInterlock
                        )
                        val admission = IsolatedRouteAdmissionHost(isolatedWorld, atoCoordinator)
                        routeAdmissionHost = admission
                        physics.registerRailwayEpochProcessor(signalEpochRuntime)
                        isolatedWorld.registerExtensionData(
                            SimpleATOPlugin.INSTANCE,
                            SimpleATOWorldData(
                                atoCoordinator,
                                dispatcherFactory(
                                    isolatedWorld,
                                    admission.contextProvider,
                                    signalRestrictions,
                                    inspectionRuntime,
                                    admission.infrastructureDependencyService
                                ),
                                signalEpochRuntime,
                                signalRestrictions,
                                inspectionRuntime,
                                basicData.railObjects,
                                basicData.safetyInterlock,
                                timetableExtensions = timetableExtensions
                            )
                        )
                        simpleAtoInfrastructure.restore(isolatedWorld)
                        seed(isolatedWorld)
                    }
                }
                physics.start()
                return IsolatedTimetableSimulationWorld(
                    world,
                    tickManager,
                    physics,
                    checkNotNull(routeAdmissionHost)
                )
            } catch (failure: Throwable) {
                routeAdmissionHost?.close()
                physics.close()
                tickManager.close()
                throw failure
            }
        }
    }
}

/** Basic's real admission runtime, composed without Bukkit-only providers for isolated simulation. */
private class IsolatedRouteAdmissionHost(
    private val world: CoreWorld,
    private val atoCoordinator: AtoWorldCoordinator
) : AutoCloseable {
    private val requester = Any()
    private var latestObservation: RailwayObservation? = null
    private var runtime: RouteResourceAdmissionRuntime? = null
    private val registry = InfrastructureCoordinationRegistry(
        currentTopologyRevision = world::topologyRevision,
        resourceExists = ::resourceExists,
        railExists = { railId -> world.getRail(railId) != null },
        topologyNodeExists = { key ->
            world.topologyState.structuralNodeWithPorts(key.ports) != null
        }
    )
    private val service = object : RouteResourceAdmissionService {
        override fun request(intent: RouteResourceIntent): RouteAdmissionResult =
            checkNotNull(runtime).request(requester, intent)

        override fun progress(handle: RouteAdmissionHandle): RouteAdmissionResult =
            checkNotNull(runtime).progress(requester, handle)

        override fun suspendReleaseForReversal(grantId: RouteGrantId): RouteGrantReversalResult =
            checkNotNull(runtime).suspendReleaseForReversal(requester, grantId)

        override fun rebaseAfterReversal(intent: RouteResourceIntent): RouteGrantReversalResult =
            checkNotNull(runtime).rebaseAfterReversal(requester, intent)

        override fun endGrant(grantId: RouteGrantId): Boolean =
            checkNotNull(runtime).endGrant(requester, grantId)
    }

    val contextProvider = TimetableRouteAdmissionContextProvider {
        latestObservation?.let { TimetableRouteAdmissionContext(it, service) }
    }

    val infrastructureDependencyService = object : InfrastructureDependencyService {
        override fun set(request: InfrastructureDependencyRequest) =
            registry.setInfrastructureDependencies(requester, request)

        override fun remove(owner: InfrastructureOwner) =
            registry.removeInfrastructureDependencies(requester, owner)
    }

    init {
        runtime = RouteResourceAdmissionRuntime.create(
            worldId = atoCoordinator.worldId,
            world = world,
            registry = registry,
            hostClaimOwner = this,
            latestObservation = { latestObservation },
            collectContributions = { emptyList() },
            validateContribution = { _, _ -> null },
            resolveContributedPreparation = { _, _ -> null },
            stallWorld = { reason, failure -> stall(reason, failure) }
        )
    }

    fun publish(observation: RailwayObservation) {
        latestObservation = observation
        checkNotNull(runtime).consumeObservation(observation)
    }

    override fun close() {
        runtime?.close()
        registry.close()
    }

    private fun resourceExists(resource: InfrastructureResource): Boolean = when (resource) {
        is RailIntervalResource -> world.getRail(resource.railId)?.curve?.totalArcLength
            ?.let { resource.endAbMeters <= it } == true
        is StableInfrastructureResource -> runtime?.physicalResourceExists(resource) == true
    }

    private fun stall(reason: String, failure: Exception) {
        atoCoordinator.stall(
            AtoStallReport(
                worldId = atoCoordinator.worldId,
                trainId = "isolated-route-admission",
                tick = latestObservation?.stamp?.physicsEpoch ?: 0L,
                errorType = "RouteAdmissionFailure",
                message = reason,
                stackTrace = failure.stackTraceToString(),
                initialTrain = null,
                currentTrain = null,
                route = null,
                perception = null,
                constraints = emptyMap()
            )
        )
    }
}

private fun TimetableServiceRunSnapshot.isSuccessfulTerminal(): Boolean =
    state == TimetableServiceRunState.COMPLETED || state == TimetableServiceRunState.STOPPED

private fun TimetableServiceRunSnapshot.observedDelayTicks(): Long = maxOf(
    lastLatenessTicks,
    nextScheduledTick?.let { (timetableElapsedTicks - it).coerceAtLeast(0L) } ?: 0L
)
