package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.infrastructure.AppliedPlanDependencies
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.infrastructure.StableTopologyNodeKey
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection

/** Result of compiling already-selected routes into non-exclusive edit dependencies. */
sealed interface PhysicalRouteAppliedPlanCompilation {
    data object NoDependencies : PhysicalRouteAppliedPlanCompilation

    data class Compiled(val dependencies: AppliedPlanDependencies) : PhysicalRouteAppliedPlanCompilation

    data class Rejected(val detail: String) : PhysicalRouteAppliedPlanCompilation {
        init {
            require(detail.isNotBlank())
        }
    }
}

/**
 * Compiles known routes against one immutable topology root without selecting another path.
 *
 * The result contains persistent rail identities, stable structural-node keys and Basic-owned
 * turnout/diamond resources. It is an edit dependency declaration, not a live train claim.
 */
object PhysicalRouteAppliedPlanCompiler {
    @JvmStatic
    fun compile(
        topology: TopologyState,
        routes: Collection<RouteSnapshot>
    ): PhysicalRouteAppliedPlanCompilation {
        if (routes.isEmpty()) return PhysicalRouteAppliedPlanCompilation.NoDependencies

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
        return PhysicalRouteAppliedPlanCompilation.Compiled(
            AppliedPlanDependencies(railIds, nodeKeys, resourceIds)
        )
    }

    private fun rejected(detail: String) = PhysicalRouteAppliedPlanCompilation.Rejected(detail)
}
