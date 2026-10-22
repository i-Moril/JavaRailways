package work.art1st.javarailways.simpleato.timetable

import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.basic.api.observation.RailwayTrainObservation
import work.art1st.javarailways.basic.api.route.RouteAdmissionHandle
import work.art1st.javarailways.basic.api.route.RouteAdmissionResult
import work.art1st.javarailways.simpleato.api.ato.AtoStallReport
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.core.api.physics.infrastructure.RailIntervalResource
import work.art1st.javarailways.core.api.physics.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencyMutationResult
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencyMutationStatus
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencyRequest
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.route.RouteGrantId
import work.art1st.javarailways.basic.api.route.RouteGrantReversalResult
import work.art1st.javarailways.basic.api.route.RouteResourceAdmissionService
import work.art1st.javarailways.basic.api.route.RouteResourceIntent
import work.art1st.javarailways.core.physics.infrastructure.InfrastructureCoordinationRegistry
import work.art1st.javarailways.basic.infrastructure.admission.RouteResourceAdmissionRuntime
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.core.api.physics.rail.RailwayOccupancySnapshot
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.physics.CoreWorld

/** Unit-test host for Basic's real admission runtime over manually published Core train frames. */
internal class GrantingRouteAdmissionTestContext(
    private val preparationFactory: (() -> work.art1st.javarailways.basic.api.route.ResourcePreparationTicket)? = null,
    private val world: () -> CoreWorld?
) : TimetableRouteAdmissionContextProvider {
    private var epoch = 0L
    private var boundWorld: CoreWorld? = null
    private var runtime: RouteResourceAdmissionRuntime? = null
    private var latestObservation: RailwayObservation? = null
    private val requester = Any()
    private val preparationResource = StableInfrastructureResource("test:station-selection", "gate")
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
                currentWorld.simpleAtoWorldData.atoCoordinator.worldId,
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
            RailwayOccupancySnapshot.create(nextEpoch, frames),
            ActiveRailContinuationsSnapshot.empty(currentWorld.topologyState.revision)
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
                        (preparationFactory != null && resource == preparationResource) || createdRuntime.physicalResourceExists(resource)
                }
            },
            railExists = { railId -> currentWorld.getRail(railId) != null },
            topologyNodeExists = { key ->
                currentWorld.topologyState.structuralNodeWithPorts(key.ports) != null
            }
        )
        createdRuntime = RouteResourceAdmissionRuntime.create(
            worldId = currentWorld.simpleAtoWorldData.atoCoordinator.worldId,
            world = currentWorld,
            registry = createdRegistry,
            hostClaimOwner = this,
            latestObservation = { latestObservation },
            collectContributions = { intent -> if (preparationFactory == null) emptyList() else listOf(
                requester to work.art1st.javarailways.basic.api.route.ContributedRouteResource(preparationResource,
                    intent.desiredAuthority, work.art1st.javarailways.basic.api.route.ResourcePreparationDescriptor(
                        "test:station-selection", emptyMap()))) },
            validateContribution = { _, _ -> null },
            resolveContributedPreparation = { resource, _ -> preparationFactory?.takeIf { resource == preparationResource } },
            stallWorld = { reason, failure ->
                val coordinator = currentWorld.simpleAtoWorldData.atoCoordinator
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
                    constraints = emptyMap()
                ))
            }
        )
        runtime = createdRuntime
    }
}

internal val UnavailableRouteAdmissionTestContext =
    TimetableRouteAdmissionContextProvider { null }

internal class RecordingInfrastructureDependencyService : InfrastructureDependencyService {
    val applied = LinkedHashMap<String, InfrastructureDependencyRequest>()
    val mutations = ArrayList<InfrastructureDependencyRequest>()
    var setFailure: InfrastructureDependencyMutationResult? = null
    var removeFailure: InfrastructureDependencyMutationResult? = null

    override fun set(request: InfrastructureDependencyRequest): InfrastructureDependencyMutationResult {
        setFailure?.let { return it }
        mutations += request
        val previous = applied.put(request.owner.ownerId, request)
        return InfrastructureDependencyMutationResult(
            if (previous == null) InfrastructureDependencyMutationStatus.APPLIED
            else InfrastructureDependencyMutationStatus.REPLACED
        )
    }

    override fun remove(owner: InfrastructureOwner): InfrastructureDependencyMutationResult {
        removeFailure?.let { return it }
        val existing = applied[owner.ownerId]
            ?: return InfrastructureDependencyMutationResult(
                InfrastructureDependencyMutationStatus.OWNER_REVISION_CONFLICT
            )
        if (existing.owner != owner) {
            return InfrastructureDependencyMutationResult(
                InfrastructureDependencyMutationStatus.OWNER_REVISION_CONFLICT
            )
        }
        applied.remove(owner.ownerId)
        return InfrastructureDependencyMutationResult(InfrastructureDependencyMutationStatus.REMOVED)
    }
}

internal fun acceptingInfrastructureDependencyService(): InfrastructureDependencyService =
    RecordingInfrastructureDependencyService()
