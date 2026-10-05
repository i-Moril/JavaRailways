package work.art1st.javarailways.simplesignals.signal

import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.core.api.physics.rail.topology.AtGradeRailCrossing
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNode
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNodeId
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.simplesignals.region.ProtectionRouteDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionRouteTableDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionRouteTraversal
import work.art1st.javarailways.simplesignals.runtime.RequiredActiveContinuation
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
 * It does not claim resources or choose a path. It only validates the authored default set and
 * records the turnout continuations needed to observe whether a configured route is physically
 * open in one coherent world frame.
 */
internal class ProtectionRouteSelectionIndex private constructor(
    val topology: TopologyState,
    routesById: Map<String, CompiledProtectionRoute>,
    defaultRoutesByEntryBoundaryId: Map<String, CompiledProtectionRoute>,
    private val compiledRoutes: Map<String, CompiledRoute>
) {
    val routesById: Map<String, CompiledProtectionRoute> = java.util.Map.copyOf(routesById)
    val defaultRoutesByEntryBoundaryId: Map<String, CompiledProtectionRoute> =
        java.util.Map.copyOf(defaultRoutesByEntryBoundaryId)

    fun defaultRoute(
        entryBoundaryId: String,
        active: ActiveRailContinuationsSnapshot
    ): CompiledProtectionRoute? {
        return defaultRoutesByEntryBoundaryId[entryBoundaryId]?.takeIf { it.isPhysicallyOpen(active) }
    }

    companion object {
        fun compile(
            topology: TopologyState,
            definitions: Collection<ProtectionRouteTableDefinition>,
            previous: ProtectionRouteSelectionIndex? = null,
            affectedRegionIds: Set<String> = emptySet()
        ): ProtectionRouteSelectionIndex {
            val routesById = linkedMapOf<String, CompiledRoute>()
            val defaultRoutes = ArrayList<CompiledRoute>()

            definitions.sortedBy(ProtectionRouteTableDefinition::tableId).forEach { table ->
                val authoredById = table.routes.associateBy(ProtectionRouteDefinition::routeId)
                require(authoredById.size == table.routes.size) {
                    "route table ${table.tableId} contains duplicate route IDs"
                }
                val unknownDefaults = table.defaultRouteIds.filterNot(authoredById::containsKey)
                require(unknownDefaults.isEmpty()) {
                    "route table ${table.tableId} references unknown default routes $unknownDefaults"
                }
                table.routes.forEach { route ->
                    val retained = previous?.compiledRoutes?.get(route.routeId)?.takeIf {
                        table.regionId !in affectedRegionIds && it.route.definition == route
                    }
                    val compiled = retained ?: compileRoute(topology, table, route)
                    require(routesById.putIfAbsent(route.routeId, compiled) == null) {
                        "protection route ${route.routeId} is defined by more than one table"
                    }
                    if (route.routeId in table.defaultRouteIds) defaultRoutes += compiled
                }
            }

            defaultRoutes.forEachIndexed { index, first ->
                for (secondIndex in index + 1 until defaultRoutes.size) {
                    val second = defaultRoutes[secondIndex]
                    require(!first.footprint.conflictsWith(second.footprint)) {
                        "default protection routes ${first.route.definition.routeId} and " +
                            "${second.route.definition.routeId} physically conflict"
                    }
                }
            }
            val defaultsByEntry = linkedMapOf<String, CompiledProtectionRoute>()
            defaultRoutes.sortedBy { it.route.definition.routeId }.forEach { compiled ->
                val route = compiled.route
                require(defaultsByEntry.putIfAbsent(route.definition.entryBoundaryId, route) == null) {
                    "conflicting default routes share entry ${route.definition.entryBoundaryId}"
                }
            }
            return ProtectionRouteSelectionIndex(
                topology,
                routesById.mapValues { it.value.route },
                defaultsByEntry,
                java.util.Map.copyOf(routesById)
            )
        }

        private fun compileRoute(
            topology: TopologyState,
            table: ProtectionRouteTableDefinition,
            definition: ProtectionRouteDefinition
        ): CompiledRoute {
            val requiredContinuations = definition.traversals.zipWithNext().mapNotNull { (from, to) ->
                requiredContinuation(topology, table, definition, from, to)
            }.distinct()
            require(requiredContinuations.groupBy(RequiredActiveContinuation::entry).all { (_, values) ->
                values.map(RequiredActiveContinuation::selectedExit).distinct().size == 1
            }) {
                "route ${definition.routeId} requires conflicting turnout continuations"
            }
            val route = CompiledProtectionRoute(
                topology.revision,
                table.tableId,
                table.regionId,
                definition,
                java.util.List.copyOf(requiredContinuations)
            )
            return CompiledRoute(route, compileFootprint(topology, definition, requiredContinuations))
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

        private fun compileFootprint(
            topology: TopologyState,
            definition: ProtectionRouteDefinition,
            requirements: List<RequiredActiveContinuation>
        ): DefaultRouteFootprint {
            val crossings = linkedSetOf<AtGradeRailCrossing>()
            definition.traversals.forEach { traversal ->
                topology.atGradeCrossingsOn(traversal.railId).forEach { crossing ->
                    val position = if (crossing.first.railId == traversal.railId) {
                        crossing.first.arcLengthAbMeters
                    } else {
                        crossing.second.arcLengthAbMeters
                    }
                    if (position >= traversal.lowerAbMeters - Curve.TOLERANCE &&
                        position <= traversal.upperAbMeters + Curve.TOLERANCE
                    ) crossings += crossing
                }
            }
            val turnoutNodes = requirements.mapTo(linkedSetOf()) { requirement ->
                checkNotNull(topology.structuralNodeAt(requirement.entry)).id
            }
            return DefaultRouteFootprint(
                java.util.List.copyOf(definition.traversals),
                java.util.Set.copyOf(turnoutNodes),
                java.util.Set.copyOf(crossings)
            )
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

    private data class CompiledRoute(
        val route: CompiledProtectionRoute,
        val footprint: DefaultRouteFootprint
    )

    private data class DefaultRouteFootprint(
        val traversals: List<ProtectionRouteTraversal>,
        val turnoutNodes: Set<TopologyNodeId>,
        val crossings: Set<AtGradeRailCrossing>
    ) {
        fun conflictsWith(other: DefaultRouteFootprint): Boolean =
            turnoutNodes.any(other.turnoutNodes::contains) ||
                crossings.any(other.crossings::contains) ||
                traversals.any { first ->
                    other.traversals.any { second -> positiveOverlap(first, second) }
                }

        private fun positiveOverlap(
            first: ProtectionRouteTraversal,
            second: ProtectionRouteTraversal
        ): Boolean = first.railId == second.railId &&
            min(first.upperAbMeters, second.upperAbMeters) -
            max(first.lowerAbMeters, second.lowerAbMeters) > Curve.TOLERANCE
    }
}
