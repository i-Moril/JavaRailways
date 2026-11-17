package work.art1st.javarailways.basic.infrastructure.admission

import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.route.ActiveRouteAdmissionSnapshot
import work.art1st.javarailways.basic.api.route.ActiveRouteAdmissionUpdate
import work.art1st.javarailways.basic.api.route.ContributedRouteResource
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailure
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailureCode
import work.art1st.javarailways.basic.api.route.RouteAdmissionHandle
import work.art1st.javarailways.basic.api.route.RouteAdmissionResult
import work.art1st.javarailways.basic.api.route.RouteGrantId
import work.art1st.javarailways.basic.api.route.RouteGrantReversalResult
import work.art1st.javarailways.basic.api.route.RouteResourceIntent
import work.art1st.javarailways.core.api.physics.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.route.ResourcePreparationDescriptor
import work.art1st.javarailways.basic.api.route.ResourcePreparationTicket
import work.art1st.javarailways.basic.api.route.FixedFormationChangeIntent
import work.art1st.javarailways.basic.api.route.FormationChangeHandle
import work.art1st.javarailways.basic.api.route.FormationChangeResult
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.api.util.annotation.MainThread
import work.art1st.javarailways.core.physics.infrastructure.InfrastructureCoordinationRegistry
import work.art1st.javarailways.basic.infrastructure.PhysicalTopologyResources
import work.art1st.javarailways.basic.infrastructure.RouteBoundResourceCatalog
import work.art1st.javarailways.basic.infrastructure.RouteBoundResourceCatalogUpdate

/**
 * Platform composition owner for one world's generic route-resource admission pipeline.
 *
 * This class lives outside `basic.api`: add-ons use the eventual authenticated Bukkit service,
 * while the platform adapter uses this owner to join the registry, physical preparation and
 * observation-driven tail release into one lifecycle.
 */
@OptIn(work.art1st.javarailways.core.api.util.annotation.AsyncEventApi::class)
class RouteResourceAdmissionRuntime private constructor(
    private val currentTopology: () -> TopologyState,
    private val routeBoundResources: RouteBoundResourceCatalog,
    private val engine: RouteResourceAdmissionEngine,
    initialTopology: TopologyState,
    private val topologyRegistration: AutoCloseable,
    private val formationRegistration: AutoCloseable,
    private val physicsFormationRegistration: AutoCloseable
) : AutoCloseable {
    private var catalogTopology = initialTopology
    private var physicalResources: Set<StableInfrastructureResource> = emptySet()
    private var closed = false

    init {
        publishPhysicalResources(initialTopology)
    }

    /** Handles both the first authority request and farther authority on the same frozen route. */
    @MainThread
    fun request(requester: Any, intent: RouteResourceIntent): RouteAdmissionResult {
        if (closed) return unavailable()
        refreshPhysicalResources()
        return engine.request(requester, intent)
    }

    /** Validates Basic-owned turnout/diamond identities without requiring an external provider. */
    @MainThread
    fun physicalResourceExists(resource: StableInfrastructureResource): Boolean {
        if (closed) return false
        refreshPhysicalResources()
        return resource in physicalResources
    }

    @MainThread
    fun progress(requester: Any, handle: RouteAdmissionHandle): RouteAdmissionResult =
        if (closed) unavailable() else engine.progress(requester, handle)

    @MainThread
    fun prepareFormationChange(requester: Any, intent: FixedFormationChangeIntent): FormationChangeResult =
        if (closed) formationUnavailable() else engine.prepareFormationChange(requester, intent)

    @MainThread
    fun sealFormationPublication(requester: Any, handle: FormationChangeHandle): FormationChangeResult =
        if (closed) formationUnavailable() else engine.sealFormationPublication(requester, handle)

    @MainThread
    fun formationChangeState(requester: Any, handle: FormationChangeHandle): FormationChangeResult =
        if (closed) formationUnavailable() else engine.formationChangeState(requester, handle)

    @MainThread
    fun abortFormationChange(requester: Any, handle: FormationChangeHandle): FormationChangeResult =
        if (closed) formationUnavailable() else engine.abortFormationChange(requester, handle)

    @MainThread
    fun bindFormationOutputs(requester: Any, handle: FormationChangeHandle, intents: List<RouteResourceIntent>): FormationChangeResult =
        if (closed) formationUnavailable() else engine.bindFormationOutputs(requester, handle, intents)

    @MainThread
    fun requestFormationOutput(requester: Any, handle: FormationChangeHandle, intent: RouteResourceIntent): RouteAdmissionResult =
        if (closed) unavailable() else engine.requestFormationOutput(requester, handle, intent)

    @MainThread
    fun resumeFormationOutput(requester: Any, handle: FormationChangeHandle, grant: RouteGrantId): FormationChangeResult =
        if (closed) formationUnavailable() else engine.resumeFormationOutput(requester, handle, grant)

    @MainThread
    fun finishFormationForManual(requester: Any, handle: FormationChangeHandle, grant: RouteGrantId): FormationChangeResult =
        if (closed) formationUnavailable() else engine.finishFormationForManual(requester, handle, grant)

    @MainThread
    fun requestCouplingApproach(requester: Any, handle: FormationChangeHandle,
                                intent: RouteResourceIntent): RouteAdmissionResult =
        if (closed) unavailable() else engine.requestCouplingApproach(requester, handle, intent)

    @MainThread
    fun suspendReleaseForReversal(
        requester: Any,
        grantId: RouteGrantId
    ): RouteGrantReversalResult =
        if (closed) reversalUnavailable() else engine.suspendReleaseForReversal(requester, grantId)

    @MainThread
    fun rebaseAfterReversal(
        requester: Any,
        intent: RouteResourceIntent
    ): RouteGrantReversalResult {
        if (closed) return reversalUnavailable()
        refreshPhysicalResources()
        return engine.rebaseAfterReversal(requester, intent)
    }

    /** Advances every committed route from one already-published coherent world observation. */
    @MainThread
    fun consumeObservation(observation: RailwayObservation) {
        if (!closed) engine.consumeObservation(observation)
    }

    @MainThread
    fun endGrant(requester: Any, grantId: RouteGrantId): Boolean =
        !closed && engine.endGrant(requester, grantId)

    @MainThread
    fun forceEndRoute(requester: Any, owner: work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner): Boolean =
        !closed && engine.forceEndRoute(requester, owner)

    /** Resource-bearing route states owned by this requester; empty completed states are omitted. */
    @MainThread
    fun requesterActivity(requester: Any): List<RouteAdmissionRequesterActivity> =
        if (closed) emptyList() else engine.requesterActivity(requester)

    /** Resource-bearing route states that still depend on one of the provider's type IDs. */
    @MainThread
    fun providerActivity(typeIds: Set<String>): List<RouteAdmissionRequesterActivity> =
        if (closed) emptyList() else engine.providerActivity(typeIds)

    /** Detached current admissions in stable logical-owner order. */
    @MainThread
    fun activeAdmissions(): List<ActiveRouteAdmissionSnapshot> =
        if (closed) emptyList() else engine.activeAdmissions()

    /** True until every route using the currently installed contributor set has ended. */
    @MainThread
    fun hasRouteState(): Boolean = !closed && engine.hasRouteState()

    /** Discards only empty route state and terminal-handle references for a departing requester. */
    @MainThread
    fun discardInactiveRequester(requester: Any): Boolean =
        closed || engine.discardInactiveRequester(requester)

    /** Retains active resources and latches this world fail-closed after forced requester loss. */
    @MainThread
    fun failClosedRequesterLoss(
        requester: Any,
        requesterDescription: String
    ): List<RouteAdmissionRequesterActivity> =
        if (closed) emptyList() else engine.failClosedRequesterLoss(requester, requesterDescription)

    /** Retains active resources and latches this world fail-closed after provider loss. */
    @MainThread
    fun failClosedProviderLoss(
        typeIds: Set<String>,
        providerDescription: String
    ): List<RouteAdmissionRequesterActivity> =
        if (closed) emptyList() else engine.failClosedProviderLoss(typeIds, providerDescription)

    /** Prevents a forced contributor loss from weakening later admissions in this world. */
    @MainThread
    fun failClosedContributorLoss(contributorDescription: String): Boolean =
        !closed && engine.failClosedContributorLoss(contributorDescription)

    /** Prevents new admission after forced loss of an infrastructure-dependency owner or provider. */
    @MainThread
    fun failClosedInfrastructureDependencyLoss(dependencyDescription: String) {
        if (!closed) engine.failClosedInfrastructureDependencyLoss(dependencyDescription)
    }

    @MainThread
    override fun close() {
        closed = true
        engine.disableFormationContact()
        try { physicsFormationRegistration.close() } finally {
            try { formationRegistration.close() } finally { topologyRegistration.close() }
        }
    }

    private fun refreshPhysicalResources() {
        val topology = currentTopology()
        if (topology === catalogTopology) return
        publishPhysicalResources(topology)
        catalogTopology = topology
    }

    private fun publishPhysicalResources(topology: TopologyState) {
        val bindings = PhysicalTopologyResources.routeBoundBindings(topology)
        check(routeBoundResources.publish(topology, bindings) is RouteBoundResourceCatalogUpdate.Published) {
            "physical route-bound resource catalog could not follow the current topology"
        }
        physicalResources = PhysicalTopologyResources.controlledResources(topology, bindings)
    }

    private fun unavailable() = RouteAdmissionResult.Rejected(
        RouteAdmissionFailure(RouteAdmissionFailureCode.WORLD_UNAVAILABLE, "route admission runtime is closed")
    )

    private fun reversalUnavailable() = RouteGrantReversalResult.Rejected(
        RouteAdmissionFailure(RouteAdmissionFailureCode.WORLD_UNAVAILABLE, "route admission runtime is closed")
    )

    private fun formationUnavailable() = FormationChangeResult.Rejected(
        RouteAdmissionFailure(RouteAdmissionFailureCode.WORLD_UNAVAILABLE, "route admission runtime is closed"))

    companion object {
        /** Creates the Basic-owned runtime before the world's physics coordinator is started. */
        @JvmStatic
        fun create(
            worldId: String,
            world: World,
            registry: InfrastructureCoordinationRegistry,
            hostClaimOwner: Any,
            latestObservation: () -> RailwayObservation?,
            collectContributions: (RouteResourceIntent) -> List<Pair<Any, ContributedRouteResource>>,
            validateContribution: (Any, ContributedRouteResource) -> RouteAdmissionFailure?,
            resolveContributedPreparation: (
                StableInfrastructureResource,
                ResourcePreparationDescriptor
            ) -> (() -> ResourcePreparationTicket)?,
            publishAdmissionUpdate: (ActiveRouteAdmissionUpdate) -> Unit = {},
            authorizeFormationRoute: (
                work.art1st.javarailways.basic.api.route.FormationRouteContext,
                RouteResourceIntent
            ) -> work.art1st.javarailways.basic.api.route.FormationRoutePolicyResult = { _, _ ->
                work.art1st.javarailways.basic.api.route.FormationRoutePolicyResult.Rejected(
                    RouteAdmissionFailure(RouteAdmissionFailureCode.WORLD_UNAVAILABLE, "formation policy is unavailable"))
            },
            stallWorld: (String, Exception) -> Unit
        ): RouteResourceAdmissionRuntime {
            val topology = world.topologyState
            val catalog = RouteBoundResourceCatalog { world.topologyState }
            val engine = RouteResourceAdmissionEngine(
                worldId = worldId,
                registry = registry,
                hostClaimOwner = hostClaimOwner,
                currentTopology = { world.topologyState },
                latestObservation = latestObservation,
                routeBoundResources = catalog,
                collectContributions = collectContributions,
                validateContribution = validateContribution,
                preparationActuator = LiveInfrastructurePreparationActuator(
                    world,
                    resolveContributedPreparation
                ),
                stallWorld = stallWorld,
                publishAdmissionUpdate = publishAdmissionUpdate,
                formationWorld = world,
                authorizeFormationRoute = authorizeFormationRoute
            )
            val registration = world.registerTopologyChangeListener { change -> engine.invalidateTopology(change) }
            var formationRegistration: AutoCloseable? = null
            var physicsFormationRegistration: AutoCloseable? = null
            return try {
                formationRegistration = work.art1st.javarailways.core.api.event.processor.MainRailEventBus.register(
                    object : work.art1st.javarailways.core.api.event.processor.EventListener {
                        @work.art1st.javarailways.core.api.event.processor.HandlesEvent
                        fun link(event: work.art1st.javarailways.core.api.event.TrainLinkEvent) {
                            if (event.world === world) engine.guardFormationLink(event)
                        }
                        @work.art1st.javarailways.core.api.event.processor.HandlesEvent
                        fun split(event: work.art1st.javarailways.core.api.event.TrainSplitEvent) {
                            if (event.world === world) engine.guardFormationSplit(event)
                        }
                        @work.art1st.javarailways.core.api.event.processor.HandlesEvent
                        fun linked(event: work.art1st.javarailways.core.api.event.TrainLinkSuccessEvent) {
                            if (event.world === world) engine.observedFormationLink(event)
                        }
                        @work.art1st.javarailways.core.api.event.processor.HandlesEvent
                        fun splitComplete(event: work.art1st.javarailways.core.api.event.TrainSplitSuccessEvent) {
                            if (event.world === world) engine.observedFormationSplit(event)
                        }
                    })
                physicsFormationRegistration = work.art1st.javarailways.core.api.event.processor.PhysicsRailEventBus.register(
                    object : work.art1st.javarailways.core.api.event.processor.EventListener {
                        @work.art1st.javarailways.core.api.event.processor.HandlesEvent
                        fun contact(event: work.art1st.javarailways.core.api.event.CollisionEvent) {
                            if (event.world === world) engine.observeFormationCollision(event)
                        }
                    })
                RouteResourceAdmissionRuntime({ world.topologyState }, catalog, engine, topology, registration,
                    formationRegistration, physicsFormationRegistration)
            } catch (failure: Exception) {
                engine.disableFormationContact()
                physicsFormationRegistration?.close()
                formationRegistration?.close()
                registration.close()
                throw failure
            }
        }
    }
}
