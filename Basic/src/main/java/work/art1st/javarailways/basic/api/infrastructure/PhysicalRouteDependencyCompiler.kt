package work.art1st.javarailways.basic.api.infrastructure

import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.infrastructure.PhysicalTopologyResources
import work.art1st.javarailways.basic.infrastructure.ProjectedEdgeDirection
import work.art1st.javarailways.basic.infrastructure.TopologyRouteProjectionResult
import work.art1st.javarailways.basic.infrastructure.TopologyRouteProjector
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencies
import work.art1st.javarailways.core.api.physics.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.core.api.physics.infrastructure.StableTopologyNodeKey
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection

/**
 * Detached result of compiling already-selected routes into non-exclusive edit dependencies.
 * First-party module contract, without independent third-party API compatibility guarantees.
 * No result registers dependencies or grants a live train claim or movement authority.
 */
sealed interface PhysicalRouteDependencyCompilation {
    /** The supplied route collection was empty; this query contributes no physical dependencies. */
    data object NoDependencies : PhysicalRouteDependencyCompilation

    /**
     * Immutable union of physical references from every supplied route; retains no live world.
     * @property dependencies Non-exclusive rail, structural-node and physical-resource references.
     */
    data class Compiled(val dependencies: InfrastructureDependencies) : PhysicalRouteDependencyCompilation

    /**
     * The compilation failed, with a nonblank diagnostic and no partial dependency result.
     * Route rejection includes its zero-based collection index, route ID and projection detail.
     * @property detail The existing projection or compilation failure diagnostic.
     */
    data class Rejected(val detail: String) : PhysicalRouteDependencyCompilation {
        init {
            require(detail.isNotBlank())
        }
    }
}

/**
 * Compiles frozen routes against one caller-captured immutable topology root without path search.
 * First-party module contract, without independent third-party API compatibility guarantees.
 *
 * The result contains persistent rail identities, stable structural-node keys and Basic-owned
 * turnout/diamond resources as non-exclusive edit dependencies, not grants or live train claims.
 * Calls are synchronous pure queries: no world access, registration, mutation or blocking I/O.
 * Callers own checking currentness and applying the result through the existing dependency service.
 * These detached runtime declarations do not define a persistence format.
 */
object PhysicalRouteDependencyCompiler {
    /**
     * Validates each already-selected traversal against [topology], using route segment distances
     * in metres local to each segment's view direction. Route revision metadata alone does not
     * determine validity; geometry and legal continuations are checked against the supplied root.
     * The caller must keep [routes] stable for this call and use one captured immutable topology.
     *
     * Work follows the supplied route occurrences/transitions and their indexed rail crossings,
     * including normalization of traversed structural-node ports; it does not search the graph.
     *
     * @return [PhysicalRouteDependencyCompilation.NoDependencies] for an empty collection,
     * or a detached immutable union when every route is valid. The first invalid route in iteration
     * order returns [PhysicalRouteDependencyCompilation.Rejected] with its existing projection
     * diagnostic; accumulated references are discarded. No dependency registration occurs here.
     */
    @JvmStatic
    fun compile(
        topology: TopologyState,
        routes: Collection<RouteSnapshot>
    ): PhysicalRouteDependencyCompilation {
        if (routes.isEmpty()) return PhysicalRouteDependencyCompilation.NoDependencies

        val railIds = linkedSetOf<java.util.UUID>()
        val nodeKeys = linkedSetOf<StableTopologyNodeKey>()
        val resourceIds = linkedSetOf<StableInfrastructureResource>()
        routes.forEachIndexed { index, route ->
            val projected = when (val result = TopologyRouteProjector.project(route, topology)) {
                is TopologyRouteProjectionResult.Projected -> result.route
                is TopologyRouteProjectionResult.Rejected -> {
                    return rejected("route #$index (${route.routeId}) is invalid: ${result.detail}")
                }
            }
            projected.occurrences.mapTo(railIds) { it.segment.railId }
            projected.occurrences.forEach { occurrence ->
                val canonicalEnd = occurrence.canonicalStartAbMeters + when (occurrence.segment.facing) {
                    ViewDirection.AB -> occurrence.segment.lengthMeters
                    ViewDirection.BA -> -occurrence.segment.lengthMeters
                }
                val minimum = minOf(occurrence.canonicalStartAbMeters, canonicalEnd) - Curve.TOLERANCE
                val maximum = maxOf(occurrence.canonicalStartAbMeters, canonicalEnd) + Curve.TOLERANCE
                topology.atGradeCrossingsOn(occurrence.segment.railId).forEach { crossing ->
                    val point = if (crossing.first.railId == occurrence.segment.railId) {
                        crossing.first
                    } else {
                        crossing.second
                    }
                    if (point.arcLengthAbMeters in minimum..maximum) {
                        resourceIds += PhysicalTopologyResources.diamondResource(crossing)
                    }
                }
            }
            projected.turnoutTraversals.forEach { traversal ->
                val node = topology.nodes.getValue(traversal.nodeId)
                nodeKeys += StableTopologyNodeKey(node.ports)
                resourceIds += traversal.resource
            }
            projected.occurrences.zipWithNext().forEach { (current, next) ->
                if (current.edgeId == next.edgeId) return@forEach
                val edge = topology.edges.getValue(current.edgeId)
                val nodeId = when (current.edgeDirection) {
                    ProjectedEdgeDirection.FIRST_TO_SECOND -> edge.second.nodeId
                    ProjectedEdgeDirection.SECOND_TO_FIRST -> edge.first.nodeId
                }
                nodeKeys += StableTopologyNodeKey(topology.nodes.getValue(nodeId).ports)
            }
        }
        if (railIds.isEmpty()) return rejected("known routes contain no persistent rails")
        return PhysicalRouteDependencyCompilation.Compiled(
            InfrastructureDependencies(railIds, nodeKeys, resourceIds)
        )
    }

    private fun rejected(detail: String) = PhysicalRouteDependencyCompilation.Rejected(detail)
}
