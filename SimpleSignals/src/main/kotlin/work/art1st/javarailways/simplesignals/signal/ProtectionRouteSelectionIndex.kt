package work.art1st.javarailways.simplesignals.signal

import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNode
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.simplesignals.region.ProtectionRouteDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionRouteTableDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionRouteTraversal
import work.art1st.javarailways.simplesignals.runtime.RequiredActiveContinuation
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

/** One fixed protection route compiled for cheap live turnout-state checks. */
internal data class CompiledProtectionRoute(
    val topologyRevision: Long,
    val tableId: String,
    val regionId: String,
    val definition: ProtectionRouteDefinition,
    val requiredContinuations: List<RequiredActiveContinuation>
) {
    fun isPhysicallyOpen(active: ActiveRailContinuationsSnapshot): Boolean {
        return requiredContinuations.all { requirement ->
            active.selectedContinuation(requirement.entry) == requirement.selectedExit
        }
    }
}

/**
 * Immutable route-selection index compiled with one world-definition publication.
 *
 * It does not claim resources or choose a path. It records the turnout continuations needed to
 * observe whether a requested route is physically open in one coherent world frame.
 */
internal class ProtectionRouteSelectionIndex private constructor(
    val topology: TopologyState,
    routesById: Map<String, CompiledProtectionRoute>
) {
    val routesById: Map<String, CompiledProtectionRoute> = java.util.Map.copyOf(routesById)

    companion object {
        fun compile(
            topology: TopologyState,
            definitions: Collection<ProtectionRouteTableDefinition>,
            previous: ProtectionRouteSelectionIndex? = null,
            affectedRegionIds: Set<String> = emptySet()
        ): ProtectionRouteSelectionIndex {
            val routesById = linkedMapOf<String, CompiledProtectionRoute>()

            definitions.sortedBy(ProtectionRouteTableDefinition::tableId).forEach { table ->
                val authoredById = table.routes.associateBy(ProtectionRouteDefinition::routeId)
                require(authoredById.size == table.routes.size) {
                    "route table ${table.tableId} contains duplicate route IDs"
                }
                table.routes.forEach { route ->
                    val retained = previous?.routesById?.get(route.routeId)?.takeIf {
                        table.regionId !in affectedRegionIds && it.definition == route
                    }
                    val compiled = retained ?: compileRoute(topology, table, route)
                    require(routesById.putIfAbsent(route.routeId, compiled) == null) {
                        "protection route ${route.routeId} is defined by more than one table"
                    }
                }
            }

            return ProtectionRouteSelectionIndex(topology, routesById)
        }

        private fun compileRoute(
            topology: TopologyState,
            table: ProtectionRouteTableDefinition,
            definition: ProtectionRouteDefinition
        ): CompiledProtectionRoute {
            val requiredContinuations = definition.traversals.zipWithNext().mapNotNull { (from, to) ->
                requiredContinuation(topology, table, definition, from, to)
            }.distinct()
            require(requiredContinuations.groupBy(RequiredActiveContinuation::entry).all { (_, values) ->
                values.map(RequiredActiveContinuation::selectedExit).distinct().size == 1
            }) {
                "route ${definition.routeId} requires conflicting turnout continuations"
            }
            return CompiledProtectionRoute(
                topology.revision,
                table.tableId,
                table.regionId,
                definition,
                java.util.List.copyOf(requiredContinuations)
            )
        }

        private fun requiredContinuation(
            topology: TopologyState,
            table: ProtectionRouteTableDefinition,
            route: ProtectionRouteDefinition,
            from: ProtectionRouteTraversal,
            to: ProtectionRouteTraversal
        ): RequiredActiveContinuation? {
            if (from.railId == to.railId) return null
            val entry = endpointAt(
                from.railId,
                from.facing,
                from.travelEndAbMeters,
                topology
            )
            val exit = enteringEndpoint(to, topology)
            val node = topology.structuralNodeAt(entry)
            if (node is TopologyNode.Turnout) {
                require(exit in node.legalContinuations.getValue(entry)) {
                    "route ${route.routeId} in table ${table.tableId} uses an illegal turnout continuation"
                }
                return RequiredActiveContinuation(entry, exit)
            }
            val ordinary = topology.ordinaryContinuationAt(entry)
            val legal = ordinary == exit || node is TopologyNode.LoopAnchor && exit in node.ports
            require(legal) {
                "route ${route.routeId} in table ${table.tableId} is not continuous"
            }
            return null
        }

        private fun endpointAt(
            railId: UUID,
            facing: ViewDirection,
            positionAbMeters: Double,
            topology: TopologyState
        ): RailEndpointRef {
            val railLength = railLength(topology, railId)
            val endpoint = when {
                abs(positionAbMeters) <= Curve.TOLERANCE -> RailEndpoint.A
                abs(positionAbMeters - railLength) <= Curve.TOLERANCE -> RailEndpoint.B
                else -> throw IllegalArgumentException("cross-rail traversal must end at a rail endpoint")
            }
            require(
                facing == ViewDirection.AB && endpoint == RailEndpoint.B ||
                    facing == ViewDirection.BA && endpoint == RailEndpoint.A
            ) { "traversal facing does not leave through its endpoint" }
            return RailEndpointRef(railId, endpoint)
        }

        private fun enteringEndpoint(
            traversal: ProtectionRouteTraversal,
            topology: TopologyState
        ): RailEndpointRef {
            val railLength = railLength(topology, traversal.railId)
            val endpoint = when {
                abs(traversal.travelStartAbMeters) <= Curve.TOLERANCE -> RailEndpoint.A
                abs(traversal.travelStartAbMeters - railLength) <= Curve.TOLERANCE -> RailEndpoint.B
                else -> throw IllegalArgumentException("cross-rail traversal must start at a rail endpoint")
            }
            return RailEndpointRef(traversal.railId, endpoint)
        }

        private fun railLength(topology: TopologyState, railId: UUID): Double =
            topology.railProjection.getValue(railId).maxOf { slice ->
                max(slice.railStartAbMeters, slice.railEndAbMeters)
            }
    }
}
