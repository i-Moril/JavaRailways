package work.art1st.javarailways.basic.infrastructure

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
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.route.ResourcePreparationDescriptor
import work.art1st.javarailways.basic.api.route.ResourcePreparationTicket
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.api.util.annotation.MainThread

/**
 * Platform composition owner for one world's generic route-resource admission pipeline.
 *
 * This class lives outside `basic.api`: add-ons use the eventual authenticated Bukkit service,
 * while the platform adapter uses this owner to join the registry, physical preparation and
 * observation-driven tail release into one lifecycle.
 */
class RouteResourceAdmissionRuntime private constructor(
    private val currentTopology: () -> TopologyState,
    private val routeBoundResources: RouteBoundResourceCatalog,
    private val engine: RouteResourceAdmissionEngine,
    initialTopology: TopologyState,
    private val topologyRegistration: AutoCloseable
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
        topologyRegistration.close()
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
                publishAdmissionUpdate = publishAdmissionUpdate
            )
            val registration = world.registerTopologyChangeListener { change -> engine.invalidateTopology(change) }
            return try {
                RouteResourceAdmissionRuntime({ world.topologyState }, catalog, engine, topology, registration)
            } catch (failure: Exception) {
                registration.close()
                throw failure
            }
        }
    }
}
