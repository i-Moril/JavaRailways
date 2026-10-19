package work.art1st.javarailways.simpleato.timetable.simulation

import work.art1st.javarailways.core.api.physics.WorldExecutionMode
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
import work.art1st.javarailways.simpleato.restriction.TrainRestrictionSnapshotProvider
import work.art1st.javarailways.simpleato.inspection.InspectionRuntime
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
import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker
import work.art1st.javarailways.core.util.TickManagerImpl
import java.util.concurrent.Executors
import work.art1st.javarailways.simpleato.runtime.OperationalWorldRuntime
import work.art1st.javarailways.basic.safety.SpeedSafetyMonitor
import work.art1st.javarailways.basic.rail.OperationalSpeedLimitSignalMaterializer
import work.art1st.javarailways.basic.api.route.ContributedRouteResource
import work.art1st.javarailways.basic.api.route.ResourcePreparationDescriptor
import work.art1st.javarailways.basic.api.route.ResourcePreparationTicket
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailure
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailureCode

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
    val routeAdmissionHost: SimulationRouteAdmissionHost,
    val operationalRuntime: OperationalWorldRuntime,
    private val installedFeatures: List<AutoCloseable>
) : AutoCloseable {
    private var logicalMillis = 0L
    private var hasSimulated = false
    private var closed = false

    fun simulate(request: IsolatedTimetableSimulationRequest): IsolatedTimetableSimulationResult =
        IsolatedSimulationThreadMarker.run {
            world.executionOwner.run {
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
        }

    private fun simulateOnIsolatedThread(
        request: IsolatedTimetableSimulationRequest
    ): IsolatedTimetableSimulationResult {
        val dispatcher = world.simpleAtoWorldData.dispatcher
        world.simpleAtoWorldData.timetables.add(request.timetable)
        val initialized = physicsCoordinator.processBetweenFrames {
            val started = dispatcher.beginTimetable(world, request.timetable.id)
            started to (started is TimetableEpochStartResult.Started &&
                dispatcher.scheduleInitialCycle(request.timetable.id))
        }
        when (val started = initialized.first) {
            is TimetableEpochStartResult.Failure -> return IsolatedTimetableSimulationResult.Failed(
                0L, started.reason
            )
            is TimetableEpochStartResult.Started -> Unit
        }
        if (!initialized.second) {
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
        val ticket = physicsCoordinator.stepSimulation(LOGICAL_TICK_MILLIS.toDouble())
        ticket.await()
        physicsCoordinator.processBetweenFrames {
            physicsCoordinator.drainBetweenFramesOperations()
            operationalRuntime.afterPublication(ticket.epoch, publishObservation = {
                routeAdmissionHost.publish(
                    RailwayObservation.fromCore(operationalRuntime.worldId, world.latestWorldFrame())
                )
            })
        }
        tickManager.drainPendingCommits().forEach { it.commit() }
    }

    private fun cycleZeroRuns(timetableId: String): List<TimetableServiceRunSnapshot> =
        world.simpleAtoWorldData.dispatcher.statuses()
            .filter { it.id.timetableId == timetableId && it.cycleIndex == 0L }
            .sortedBy { it.id.serviceId }

    override fun close() = IsolatedSimulationThreadMarker.run {
        world.executionOwner.run closeOwner@ {
            if (closed) return@closeOwner
            closeSimulationResources(physicsCoordinator, tickManager, world.runtimeId) {
                closed = true
                closeOwned(installedFeatures.asReversed() + listOf(
                    routeAdmissionHost, operationalRuntime,
                    AutoCloseable { world.releaseRuntimeBinding() }
                ))
            }
        }
    }

    companion object {
        private const val LOGICAL_TICK_MILLIS = 50L

        fun create(
            coreInfrastructure: CoreWorld.SavedInfrastructureV2,
            basicInfrastructure: BasicInfrastructureSnapshotV2,
            simpleAtoInfrastructure: SimpleATOInfrastructureSnapshotV1,
            workerThreads: Int,
            basicDataFactory: (CoreWorld, (Set<java.util.UUID>) -> Unit) -> BasicWorldData,
            timetableExtensions: (CoreWorld) -> TimetableExtensionRegistry = { TimetableExtensionRegistry() },
            speedSafety: SpeedSafetyMonitor.Config = SpeedSafetyMonitor.Config(false, 0.0, 1),
            preparedFeatures: List<PreparedSimulationFeature> = emptyList(),
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
                automaticFallback = false
            )
            var routeAdmissionHost: SimulationRouteAdmissionHost? = null
            var operationalRuntime: OperationalWorldRuntime? = null
            var createdWorld: CoreWorld? = null
            val installed = arrayListOf<AutoCloseable>()
            try {
                val world = IsolatedSimulationThreadMarker.run {
                    CoreWorld.load(
                        coreInfrastructure,
                        runtime = null,
                        dependency = CoreWorld.SnapshotDependency(
                            tickManager,
                            NoOpWorldParentContext,
                            physics,
                            WorldExecutionMode.SIMULATION
                        )
                    ).also { isolatedWorld -> isolatedWorld.executionOwner.run {
                        createdWorld = isolatedWorld
                        val domain = OperationalWorldRuntime.create(
                            isolatedWorld, isolatedWorld.runtimeId,
                            basicDataFactory = { changes -> basicDataFactory(isolatedWorld, changes) },
                            timetableExtensions = timetableExtensions(isolatedWorld),
                            dispatcherFactory = { runtime ->
                                val admission = SimulationRouteAdmissionHost(isolatedWorld, runtime)
                                routeAdmissionHost = admission
                                dispatcherFactory(
                                    isolatedWorld, admission.contextProvider, runtime.signalRestrictions,
                                    runtime.inspectionRuntime, admission.infrastructureDependencyService
                                )
                            }
                        )
                        operationalRuntime = domain
                        basicInfrastructure.restore(isolatedWorld)
                        simpleAtoInfrastructure.restore(isolatedWorld)
                        OperationalSpeedLimitSignalMaterializer.materialize(isolatedWorld)
                        domain.attachSpeedSafety(speedSafety)
                        val target = SimulationDomainTarget(isolatedWorld, domain, checkNotNull(routeAdmissionHost))
                        preparedFeatures.forEach { installed += it.install(target) }
                        seed(isolatedWorld)
                    } }
                }
                physics.start()
                return IsolatedTimetableSimulationWorld(
                    world,
                    tickManager,
                    physics,
                    checkNotNull(routeAdmissionHost),
                    checkNotNull(operationalRuntime),
                    installed.toList()
                )
            } catch (failure: Throwable) {
                IsolatedSimulationThreadMarker.run {
                    closeSimulationResources(physics, tickManager, createdWorld?.runtimeId ?: "simulation-construction") {
                        closeOwned(installed.asReversed() + listOfNotNull(
                            routeAdmissionHost, operationalRuntime,
                            createdWorld?.let { target -> AutoCloseable { target.releaseRuntimeBinding() } }
                        ), failure)
                    }
                }
                throw failure
            }
        }
    }
}

/** Basic's real admission runtime, composed without Bukkit-only providers for isolated simulation. */
class SimulationRouteAdmissionHost internal constructor(
    private val world: CoreWorld,
    private val domain: OperationalWorldRuntime
) : AutoCloseable {
    private val providers = linkedMapOf<String, SimulationResourceProvider>()
    private val contributors = linkedMapOf<String, (RouteResourceIntent) -> List<ContributedRouteResource>>()
    private val requester = Any()

    fun registerProvider(typeId: String, provider: SimulationResourceProvider): AutoCloseable {
        check(providers.putIfAbsent(typeId, provider) == null) { "resource provider already installed: $typeId" }
        return AutoCloseable { providers.remove(typeId, provider) }
    }

    fun registerContributor(
        id: String,
        collect: (RouteResourceIntent) -> List<ContributedRouteResource>
    ): AutoCloseable {
        check(contributors.putIfAbsent(id, collect) == null) { "route contributor already installed: $id" }
        return AutoCloseable { contributors.remove(id, collect) }
    }

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
        try {
            runtime = RouteResourceAdmissionRuntime.create(
                worldId = domain.worldId,
                world = world,
                registry = registry,
                hostClaimOwner = this,
                latestObservation = { latestObservation },
                collectContributions = { intent -> contributors.entries.sortedBy { it.key }.flatMap { (id, collect) ->
                    collect(intent).map { id to it }
                } },
                validateContribution = { contributor, contribution ->
                    val provider = providers[contribution.resource.typeId]
                    if (provider != null) provider.validate(contributor as String, contribution)
                    else RouteAdmissionFailure(
                        RouteAdmissionFailureCode.INVALID_CONTRIBUTION,
                        "no simulation resource provider: ${contribution.resource.typeId}"
                    )
                },
                resolveContributedPreparation = { resource, descriptor ->
                    providers[resource.typeId]?.prepare(resource, descriptor)
                },
                publishAdmissionUpdate = domain.signalEpochRuntime::publishAdmissionUpdate,
                stallWorld = { reason, failure -> stall(reason, failure) }
            )
        } catch (failure: Throwable) {
            closeOwned(listOf(AutoCloseable { registry.close() }), failure)
            throw failure
        }
    }

    fun publish(observation: RailwayObservation) {
        latestObservation = observation
        checkNotNull(runtime).consumeObservation(observation)
    }

    override fun close() {
        closeOwned(listOf(AutoCloseable { runtime?.close() }, AutoCloseable { registry.close() }))
        providers.clear()
        contributors.clear()
    }

    private fun resourceExists(resource: InfrastructureResource): Boolean = when (resource) {
        is RailIntervalResource -> world.getRail(resource.railId)?.curve?.totalArcLength
            ?.let { resource.endAbMeters <= it } == true
        is StableInfrastructureResource -> runtime?.physicalResourceExists(resource) == true ||
            providers[resource.typeId]?.exists(resource) == true
    }

    private fun stall(reason: String, failure: Exception) {
        domain.atoCoordinator.stall(
            AtoStallReport(
                worldId = domain.worldId,
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

/** Detached definitions install fresh state against the target's real domain endpoints. */
interface PreparedSimulationFeature {
    val id: String
    fun install(target: SimulationDomainTarget): AutoCloseable
}

class SimulationDomainTarget internal constructor(
    val world: CoreWorld,
    val domain: OperationalWorldRuntime,
    val routeAdmission: SimulationRouteAdmissionHost
)

interface SimulationResourceProvider {
    fun exists(resource: StableInfrastructureResource): Boolean
    fun validate(
        contributorId: String,
        contribution: ContributedRouteResource
    ): RouteAdmissionFailure?
    fun prepare(
        resource: StableInfrastructureResource,
        descriptor: ResourcePreparationDescriptor
    ): (() -> ResourcePreparationTicket)?
}

private fun closeOwned(resources: List<AutoCloseable>, primary: Throwable? = null) {
    var failure = primary
    resources.forEach { resource ->
        try { resource.close() } catch (cleanup: Throwable) {
            if (failure == null) failure = cleanup else if (failure !== cleanup) failure.addSuppressed(cleanup)
        }
    }
    if (primary == null) failure?.let { throw it }
}

/** Worker termination is confirmed before any callback-owned domain state is released. */
private fun closeSimulationResources(
    physics: PhysicsCoordinator,
    ticks: TickManagerImpl,
    runtimeId: String,
    releaseResources: () -> Unit
) {
    var interrupted = Thread.interrupted()
    try {
        physics.closeAndAwaitTermination()
        interrupted = Thread.interrupted() || interrupted
        ticks.closeAndAwaitTermination(runtimeId)
        interrupted = Thread.interrupted() || interrupted
        releaseResources()
    } finally {
        if (Thread.interrupted() || interrupted) Thread.currentThread().interrupt()
    }
}
