package work.art1st.javarailways.simpleato.timetable.simulation

import work.art1st.javarailways.simpleato.api.simulation.PreparedSimulationFeature
import work.art1st.javarailways.simpleato.api.simulation.SimulationResourceProvider
import work.art1st.javarailways.core.api.physics.WorldExecutionMode
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.simpleato.api.ato.AtoStallReport
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureResource
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencyRequest
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner
import work.art1st.javarailways.core.api.physics.infrastructure.RailIntervalResource
import work.art1st.javarailways.core.api.physics.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.route.RouteAdmissionHandle
import work.art1st.javarailways.basic.api.route.RouteAdmissionResult
import work.art1st.javarailways.basic.api.route.RouteGrantId
import work.art1st.javarailways.basic.api.route.RouteGrantReversalResult
import work.art1st.javarailways.basic.api.route.RouteResourceAdmissionService
import work.art1st.javarailways.basic.api.route.RouteResourceIntent
import work.art1st.javarailways.basic.infrastructure.admission.RouteResourceAdmissionRuntime
import work.art1st.javarailways.basic.persistence.BasicInfrastructureSnapshotV2
import work.art1st.javarailways.simpleato.persistence.SimpleATOInfrastructureSnapshotV1
import work.art1st.javarailways.simpleato.timetable.Timetable
import work.art1st.javarailways.simpleato.api.station.StationCallResolverRegistry
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
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker
import work.art1st.javarailways.core.util.TickManagerImpl
import java.util.concurrent.Executors
import work.art1st.javarailways.simpleato.runtime.OperationalWorldRuntime
import work.art1st.javarailways.basic.safety.SpeedSafetyMonitor
import work.art1st.javarailways.basic.rail.OperationalSpeedLimitSignalMaterializer
import work.art1st.javarailways.basic.api.route.FixedFormationChangeIntent
import work.art1st.javarailways.basic.api.route.FormationChangeHandle
import work.art1st.javarailways.basic.api.route.FormationChangeResult
import work.art1st.javarailways.basic.api.route.FormationRouteContext
import work.art1st.javarailways.basic.api.route.FormationRoutePolicyResult
import work.art1st.javarailways.basic.api.route.FormationRoutePurpose
import work.art1st.javarailways.basic.api.route.FormationResourceClearance
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
        override val runs: List<TimetableServiceRunSnapshot> = emptyList(),
        val reasonCode: String? = null
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
    private val installedFeatures: List<AutoCloseable>,
    private val operationResource: AutoCloseable?
) : AutoCloseable {
    private var logicalMillis = 0L
    private var hasSimulated = false
    private var closed = false

    fun simulate(
        request: IsolatedTimetableSimulationRequest,
        afterTick: ((Long) -> Unit)? = null
    ): IsolatedTimetableSimulationResult =
        IsolatedSimulationThreadMarker.run {
            world.executionOwner.run {
                check(!hasSimulated) { "an isolated simulation world is single-use" }
                hasSimulated = true
                try {
                    simulateOnIsolatedThread(request, afterTick)
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
        request: IsolatedTimetableSimulationRequest,
        afterTick: ((Long) -> Unit)?
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
                    stall.message.orEmpty(),
                    runs = runs,
                    reasonCode = stall.errorType
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
            // Optional bounded measurement of the same completed frame, never another tick loop.
            afterTick?.invoke(tick)
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
                closeOwned(installedFeatures.asReversed() + listOfNotNull(
                    operationResource, routeAdmissionHost, operationalRuntime
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
            stationCallResolvers: (CoreWorld) -> StationCallResolverRegistry = { StationCallResolverRegistry() },
            speedSafety: SpeedSafetyMonitor.Config = SpeedSafetyMonitor.Config(false, 0.0, 1),
            preparedFeatures: List<PreparedSimulationFeature> = emptyList(),
            operationResourceFactory: (CoreWorld) -> AutoCloseable? = { null },
            candidateFacts: (work.art1st.javarailways.core.api.physics.train.Train) ->
                work.art1st.javarailways.simpleato.api.depot.TrainCandidateFacts? = { null },
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
            var operationResource: AutoCloseable? = null
            val installed = arrayListOf<AutoCloseable>()
            try {
                val world = IsolatedSimulationThreadMarker.run {
                    CoreWorld.load(
                        coreInfrastructure,
                        runtime = null,
                        dependency = CoreWorld.SnapshotDependency(
                            tickManager,
                            physics,
                            WorldExecutionMode.SIMULATION
                        )
                    ).also { isolatedWorld -> isolatedWorld.executionOwner.run {
                        createdWorld = isolatedWorld
                        operationResource = operationResourceFactory(isolatedWorld)
                        val domain = OperationalWorldRuntime.create(
                            isolatedWorld, isolatedWorld.runtimeId,
                            basicDataFactory = { changes -> basicDataFactory(isolatedWorld, changes) },
                            timetableExtensions = timetableExtensions(isolatedWorld),
                            stationCallResolvers = stationCallResolvers(isolatedWorld),
                            candidateFacts = candidateFacts,
                            dispatcherFactory = { runtime ->
                                val admission = SimulationRouteAdmissionHost(isolatedWorld, runtime)
                                routeAdmissionHost = admission
                                dispatcherFactory(
                                    isolatedWorld, admission.contextProvider, runtime.signalRestrictions,
                                    runtime.inspectionRuntime, admission.infrastructureDependencyService
                                )
                            }
                        )
                        domain.data.controlRuntime.simulation = true
                        operationalRuntime = domain
                        basicInfrastructure.restore(isolatedWorld)
                        simpleAtoInfrastructure.restore(isolatedWorld)
                        OperationalSpeedLimitSignalMaterializer.materialize(isolatedWorld)
                        domain.attachSpeedSafety(speedSafety)
                        val target = DefaultSimulationDomainTarget(isolatedWorld, domain, checkNotNull(routeAdmissionHost))
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
                    installed.toList(),
                    operationResource
                )
            } catch (failure: Throwable) {
                IsolatedSimulationThreadMarker.run {
                    closeSimulationResources(physics, tickManager, createdWorld?.runtimeId ?: "simulation-construction") {
                        closeOwned(installed.asReversed() + listOfNotNull(
                            operationResource, routeAdmissionHost, operationalRuntime
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
    private val formationContributors = linkedMapOf<String, (FormationRouteContext, RouteResourceIntent) -> RouteAdmissionFailure?>()
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

    fun registerFormationContributor(id: String, collect: (RouteResourceIntent) -> List<ContributedRouteResource>,
        authorize: (FormationRouteContext, RouteResourceIntent) -> RouteAdmissionFailure?): AutoCloseable {
        val contribution = registerContributor(id, collect)
        formationContributors[id] = authorize
        return AutoCloseable {
            contribution.close()
            formationContributors.remove(id, authorize)
        }
    }

    internal fun authorizeFormationRoute(context: FormationRouteContext, intent: RouteResourceIntent): FormationRoutePolicyResult {
        fun rejected(detail: String) = FormationRoutePolicyResult.Rejected(
            RouteAdmissionFailure(RouteAdmissionFailureCode.INVALID_CONTRIBUTION, detail))
        val capturedContributors = contributors.toMap()
        val capturedFormation = formationContributors.toMap()
        val capturedProviders = providers.toMap()
        try {
            if (intent.formationContext !== context || intent.observationStamp.worldId != world.runtimeId)
                return rejected("simulation formation context does not match intent")
            for (id in capturedContributors.keys) {
                val authorize = capturedFormation[id] ?: return rejected("simulation contributor has not opted into formation routes: $id")
                authorize(context, intent)?.let { return FormationRoutePolicyResult.Rejected(it) }
            }
            val clearances = arrayListOf<FormationResourceClearance>()
            if (context.purpose != FormationRoutePurpose.COUPLING_APPROACH) {
                for (resource in context.retainedStableResources) {
                    val provider = capturedProviders[resource.typeId] ?: return rejected("missing simulation resource provider")
                    if (!provider.exists(resource)) return rejected("missing simulation formation resource")
                    when (val result = provider.authorizeFormationResource(resource, context, intent)) {
                        is FormationRoutePolicyResult.Rejected -> return result
                        is FormationRoutePolicyResult.Accepted -> {
                            if (result.clearances.size != 1 || result.clearances.single().resource != resource ||
                                result.clearances.single().releaseAfterTailPasses?.distanceFromRouteStartMeters
                                    ?.let { it > intent.route.totalLengthMeters } == true)
                                return rejected("invalid simulation resource clearance")
                            clearances += result.clearances
                        }
                    }
                }
            }
            if (contributors != capturedContributors || formationContributors != capturedFormation || providers != capturedProviders)
                return rejected("simulation policy registrations changed during authorization")
            return FormationRoutePolicyResult.Accepted(clearances)
        } catch (failure: Exception) {
            return rejected("simulation formation policy failed: ${failure.message ?: failure.javaClass.simpleName}")
        }
    }

    private var latestObservation: RailwayObservation? = null
    private var runtime: RouteResourceAdmissionRuntime? = null
    private val registry = world.infrastructureCoordination
    private val resourceValidation = registry.registerResourceValidator(::resourceExists)
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

        override fun prepareFormationChange(intent: FixedFormationChangeIntent): FormationChangeResult =
            checkNotNull(runtime).prepareFormationChange(requester, intent)
        override fun sealFormationPublication(handle: FormationChangeHandle): FormationChangeResult =
            checkNotNull(runtime).sealFormationPublication(requester, handle)
        override fun formationChangeState(handle: FormationChangeHandle): FormationChangeResult =
            checkNotNull(runtime).formationChangeState(requester, handle)
        override fun abortFormationChange(handle: FormationChangeHandle): FormationChangeResult =
            checkNotNull(runtime).abortFormationChange(requester, handle)
        override fun requestCouplingApproach(handle: FormationChangeHandle, intent: RouteResourceIntent): RouteAdmissionResult =
            checkNotNull(runtime).requestCouplingApproach(requester, handle, intent)
        override fun bindFormationOutputs(handle: FormationChangeHandle, outputBodyIntents: List<RouteResourceIntent>): FormationChangeResult =
            checkNotNull(runtime).bindFormationOutputs(requester, handle, outputBodyIntents)
        override fun requestFormationOutput(handle: FormationChangeHandle, intent: RouteResourceIntent): RouteAdmissionResult =
            checkNotNull(runtime).requestFormationOutput(requester, handle, intent)
        override fun resumeFormationOutput(handle: FormationChangeHandle, outputGrantId: RouteGrantId): FormationChangeResult =
            checkNotNull(runtime).resumeFormationOutput(requester, handle, outputGrantId)
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
                authorizeFormationRoute = ::authorizeFormationRoute,
                resolveContributedPreparation = { resource, descriptor ->
                    providers[resource.typeId]?.prepare(resource, descriptor)
                },
                publishAdmissionUpdate = domain.signalEpochRuntime::publishAdmissionUpdate,
                stallWorld = { reason, failure -> stall(reason, failure) }
            )
        } catch (failure: Throwable) {
            closeOwned(listOf(resourceValidation), failure)
            throw failure
        }
    }

    fun publish(observation: RailwayObservation) {
        latestObservation = observation
        checkNotNull(runtime).consumeObservation(observation)
    }

    override fun close() {
        val dependencies = registry.dependenciesOwnedBy(requester).map { dependency -> AutoCloseable {
            val removed = registry.removeInfrastructureDependencies(requester, dependency.owner)
            check(removed.successful) { "could not retire simulation dependency: ${removed.detail}" }
        } }
        closeOwned(listOf(
            AutoCloseable { runtime?.close() },
            AutoCloseable { registry.releasePlatformOwner(this) }
        ) + dependencies + resourceValidation)
        providers.clear()
        contributors.clear()
        formationContributors.clear()
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
