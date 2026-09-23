package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.route.ContributedRouteResource
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailure
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailureCode
import work.art1st.javarailways.basic.api.route.RouteAdmissionHandle
import work.art1st.javarailways.basic.api.route.RouteAdmissionResult
import work.art1st.javarailways.basic.api.route.RouteGrantId
import work.art1st.javarailways.basic.api.route.RouteResourceIntent
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
    initialTopology: TopologyState
) : AutoCloseable {
    private var catalogTopology = initialTopology
    private var closed = false

    /** Handles both the first authority request and farther authority on the same frozen route. */
    @MainThread
    fun request(requester: Any, intent: RouteResourceIntent): RouteAdmissionResult {
        if (closed) return unavailable()
        refreshEmptyRouteBoundCatalog()
        return engine.request(requester, intent)
    }

    @MainThread
    fun progress(requester: Any, handle: RouteAdmissionHandle): RouteAdmissionResult =
        if (closed) unavailable() else engine.progress(requester, handle)

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

    @MainThread
    override fun close() {
        closed = true
    }

    /**
     * Until route-bound diamond resources have a production publisher, the complete catalog is
     * empty. Republish that complete-empty snapshot after a topology edit instead of leaving every
     * later request permanently bound to the old topology root.
     */
    private fun refreshEmptyRouteBoundCatalog() {
        val topology = currentTopology()
        if (topology === catalogTopology) return
        check(routeBoundResources.publish(topology, emptyList()) is RouteBoundResourceCatalogUpdate.Published) {
            "complete-empty route-bound resource catalog could not follow the current topology"
        }
        catalogTopology = topology
    }

    private fun unavailable() = RouteAdmissionResult.Rejected(
        RouteAdmissionFailure(RouteAdmissionFailureCode.WORLD_UNAVAILABLE, "route admission runtime is closed")
    )

    companion object {
        /** Creates the Basic-owned runtime before the world's physics coordinator is started. */
        @JvmStatic
        fun create(
            worldId: String,
            world: World,
            registry: InfrastructureLeaseRegistry,
            hostClaimOwner: Any,
            latestObservation: () -> RailwayObservation?,
            collectContributions: (RouteResourceIntent) -> List<Pair<Any, ContributedRouteResource>>,
            validateContribution: (Any, ContributedRouteResource) -> RouteAdmissionFailure?,
            stallWorld: (String, Exception) -> Unit
        ): RouteResourceAdmissionRuntime {
            val topology = world.topologyState
            val catalog = RouteBoundResourceCatalog { world.topologyState }
            check(catalog.publish(topology, emptyList()) is RouteBoundResourceCatalogUpdate.Published) {
                "initial complete-empty route-bound resource catalog could not be published"
            }
            val engine = RouteResourceAdmissionEngine(
                worldId = worldId,
                registry = registry,
                hostClaimOwner = hostClaimOwner,
                currentTopology = { world.topologyState },
                latestObservation = latestObservation,
                routeBoundResources = catalog,
                collectContributions = collectContributions,
                validateContribution = validateContribution,
                preparationActuator = LiveInfrastructurePreparationActuator(world),
                stallWorld = stallWorld
            )
            return RouteResourceAdmissionRuntime({ world.topologyState }, catalog, engine, topology)
        }
    }
}
