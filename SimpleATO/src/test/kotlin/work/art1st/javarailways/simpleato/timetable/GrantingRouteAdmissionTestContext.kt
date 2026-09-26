package work.art1st.javarailways.simpleato.timetable

import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.basic.api.observation.RailwayTrainObservation
import work.art1st.javarailways.basic.api.route.RouteAdmissionHandle
import work.art1st.javarailways.basic.api.route.RouteAdmissionResult
import work.art1st.javarailways.basic.api.ato.AtoStallReport
import work.art1st.javarailways.basic.api.infrastructure.RailIntervalResource
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.infrastructure.AppliedPlanDependencyMutationResult
import work.art1st.javarailways.basic.api.infrastructure.AppliedPlanDependencyMutationStatus
import work.art1st.javarailways.basic.api.infrastructure.AppliedPlanDependencyRequest
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.route.RouteGrantId
import work.art1st.javarailways.basic.api.route.RouteGrantReversalResult
import work.art1st.javarailways.basic.api.route.RouteResourceAdmissionService
import work.art1st.javarailways.basic.api.route.RouteResourceIntent
import work.art1st.javarailways.basic.infrastructure.InfrastructureCoordinationRegistry
import work.art1st.javarailways.basic.infrastructure.RouteResourceAdmissionRuntime
import work.art1st.javarailways.basic.pathFinding.basicWorldData
import work.art1st.javarailways.core.api.physics.rail.RailwayOccupancySnapshot
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.physics.CoreWorld

/** Unit-test host for Basic's real admission runtime over manually published Core train frames. */
internal class GrantingRouteAdmissionTestContext(
    private val world: () -> CoreWorld?
) : TimetableRouteAdmissionContextProvider {
    private var epoch = 0L
    private var boundWorld: CoreWorld? = null
    private var runtime: RouteResourceAdmissionRuntime? = null
    private var latestObservation: RailwayObservation? = null
    private val requester = Any()
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

    override fun current(): TimetableRouteAdmissionContext? {
        val currentWorld = world() ?: return null
        bind(currentWorld)
        val nextEpoch = ++epoch
        val frames = currentWorld.allTrains.sortedBy { it.id }.map { train ->
            val published = train.publishedFrame
            TrainFrameSnapshot.create(
                trainId = train.id,
                trainName = train.name,
                revision = nextEpoch,
                physicsEpoch = nextEpoch,
                speedMetersPerSecond = train.speed,
                derailed = train.isDerailed,
                reversed = train.isReversed,
                cars = published.cars,
                head = published.head,
                tail = published.tail,
                occupiedIntervals = published.occupiedIntervals,
                bodyTraversal = published.bodyTraversal,
                sweptSpans = published.sweptSpans,
                headTraversal = published.headTraversal,
                tailTraversal = published.tailTraversal
            )
        }
        val observation = RailwayObservation.create(
            RailwayStateStamp(
                currentWorld.basicWorldData.atoCoordinator.worldId,
                nextEpoch,
                currentWorld.topologyState.revision
            ),
            frames.map { frame ->
                RailwayTrainObservation.create(
                    trainId = frame.trainId,
                    trainName = frame.trainName,
                    frameRevision = frame.revision,
                    speedMetersPerSecond = frame.speedMetersPerSecond,
                    derailed = frame.derailed,
                    reversed = frame.reversed,
                    head = frame.head,
                    tail = frame.tail,
                    occupiedIntervals = frame.occupiedIntervals,
                    bodyTraversal = frame.bodyTraversal,
                    sweptSpans = frame.sweptSpans,
                    headTraversal = frame.headTraversal,
                    tailTraversal = frame.tailTraversal
                )
            },
            RailwayOccupancySnapshot.create(nextEpoch, frames)
        )
        latestObservation = observation
        checkNotNull(runtime).consumeObservation(observation)
        return TimetableRouteAdmissionContext(observation, service)
    }

    private fun bind(currentWorld: CoreWorld) {
        check(boundWorld == null || boundWorld === currentWorld) {
            "test route-admission context cannot be shared between worlds"
        }
        if (runtime != null) return
        boundWorld = currentWorld
        lateinit var createdRuntime: RouteResourceAdmissionRuntime
        val createdRegistry = InfrastructureCoordinationRegistry(
            currentTopologyRevision = currentWorld::topologyRevision,
            resourceExists = { resource ->
                when (resource) {
                    is RailIntervalResource -> currentWorld.getRail(resource.railId)
                        ?.curve?.totalArcLength?.let { resource.endAbMeters <= it } == true
                    is StableInfrastructureResource ->
                        createdRuntime.physicalResourceExists(resource)
                }
            },
            railExists = { railId -> currentWorld.getRail(railId) != null },
            topologyNodeExists = { key ->
                currentWorld.topologyState.structuralNodeWithPorts(key.ports) != null
            }
        )
        createdRuntime = RouteResourceAdmissionRuntime.create(
            worldId = currentWorld.basicWorldData.atoCoordinator.worldId,
            world = currentWorld,
            registry = createdRegistry,
            hostClaimOwner = this,
            latestObservation = { latestObservation },
            collectContributions = { emptyList() },
            validateContribution = { _, _ -> null },
            resolveContributedPreparation = { _, _ -> null },
            stallWorld = { reason, failure ->
                val coordinator = currentWorld.basicWorldData.atoCoordinator
                coordinator.stall(AtoStallReport(
                    worldId = coordinator.worldId,
                    trainId = "test-route-admission",
                    tick = latestObservation?.stamp?.physicsEpoch ?: 0L,
                    errorType = "RouteAdmissionFailure",
                    message = reason,
                    stackTrace = failure.stackTraceToString(),
                    initialTrain = null,
                    currentTrain = null,
                    route = null,
                    perception = null,
                    activeProviderId = null,
                    constraints = emptyMap()
                ))
            }
        )
        runtime = createdRuntime
    }
}

internal val UnavailableRouteAdmissionTestContext =
    TimetableRouteAdmissionContextProvider { null }

internal class RecordingTimetableAppliedPlanService : TimetableAppliedPlanService {
    val applied = LinkedHashMap<String, AppliedPlanDependencyRequest>()
    var setFailure: AppliedPlanDependencyMutationResult? = null

    override fun set(request: AppliedPlanDependencyRequest): AppliedPlanDependencyMutationResult {
        setFailure?.let { return it }
        val previous = applied.put(request.owner.ownerId, request)
        return AppliedPlanDependencyMutationResult(
            if (previous == null) AppliedPlanDependencyMutationStatus.APPLIED
            else AppliedPlanDependencyMutationStatus.REPLACED
        )
    }

    override fun remove(owner: InfrastructureOwner): AppliedPlanDependencyMutationResult {
        val existing = applied[owner.ownerId]
            ?: return AppliedPlanDependencyMutationResult(
                AppliedPlanDependencyMutationStatus.OWNER_REVISION_CONFLICT
            )
        if (existing.owner != owner) {
            return AppliedPlanDependencyMutationResult(
                AppliedPlanDependencyMutationStatus.OWNER_REVISION_CONFLICT
            )
        }
        applied.remove(owner.ownerId)
        return AppliedPlanDependencyMutationResult(AppliedPlanDependencyMutationStatus.REMOVED)
    }
}

internal fun acceptingTimetableAppliedPlanService(): TimetableAppliedPlanService =
    RecordingTimetableAppliedPlanService()
