package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyEdgeEnd
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNode
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNodeId
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.api.physics.rail.topology.TrackEdge
import work.art1st.javarailways.core.api.physics.rail.topology.TrackEdgeId
import work.art1st.javarailways.core.api.physics.rail.topology.TrackEdgeSlice
import kotlin.math.abs

internal enum class ProjectedEdgeDirection {
    FIRST_TO_SECOND,
    SECOND_TO_FIRST
}

/** One caller-selected rail segment projected onto its normalized topology edge. */
internal data class ProjectedRouteOccurrence(
    val segment: RouteSegmentSnapshot,
    val edgeId: TrackEdgeId,
    val edgeDirection: ProjectedEdgeDirection,
    val edgeStartMeters: Double,
    val edgeEndMeters: Double,
    val railLengthMeters: Double,
    val canonicalStartAbMeters: Double,
    val canonicalEndAbMeters: Double,
    val routeStartMeters: Double,
    val routeEndMeters: Double
)

/** A legal caller-selected traversal through one turnout node. */
internal data class ProjectedTurnoutTraversal(
    val nodeId: TopologyNodeId,
    val fromPort: RailEndpointRef,
    val toPort: RailEndpointRef,
    val routeDistanceMeters: Double
)

/** Immutable result of validating one frozen route against exactly one topology root. */
@ConsistentCopyVisibility
internal data class ProjectedTopologyRoute private constructor(
    val topologyRevision: Long,
    val route: RouteSnapshot,
    val occurrences: List<ProjectedRouteOccurrence>,
    val turnoutTraversals: List<ProjectedTurnoutTraversal>
) {
    companion object {
        fun create(
            topologyRevision: Long,
            route: RouteSnapshot,
            occurrences: Collection<ProjectedRouteOccurrence>,
            turnoutTraversals: Collection<ProjectedTurnoutTraversal>
        ): ProjectedTopologyRoute = ProjectedTopologyRoute(
            topologyRevision = topologyRevision,
            route = route,
            occurrences = java.util.List.copyOf(occurrences),
            turnoutTraversals = java.util.List.copyOf(turnoutTraversals)
        )
    }
}

internal enum class TopologyRouteProjectionRejection {
    STALE_TOPOLOGY,
    INVALID_ROUTE
}

internal sealed interface TopologyRouteProjectionResult {
    data class Projected(val route: ProjectedTopologyRoute) : TopologyRouteProjectionResult

    data class Rejected(
        val reason: TopologyRouteProjectionRejection,
        val detail: String
    ) : TopologyRouteProjectionResult {
        init {
            require(detail.isNotBlank())
        }
    }
}

/**
 * Pure linear validator/projector for an already selected route.
 *
 * This object neither searches for an alternative path nor reads or mutates live turnout state.
 */
internal object TopologyRouteProjector {
    fun project(
        route: RouteSnapshot,
        topology: TopologyState
    ): TopologyRouteProjectionResult {
        if (topology.revision != route.topologyRevision) {
            return rejected(
                TopologyRouteProjectionRejection.STALE_TOPOLOGY,
                "route expects topology revision ${route.topologyRevision}, found ${topology.revision}"
            )
        }
        if (route.segments.isEmpty()) {
            return invalid("route ${route.routeId} has no segments")
        }

        val occurrences = ArrayList<ProjectedRouteOccurrence>(route.segments.size)
        val turnoutTraversals = ArrayList<ProjectedTurnoutTraversal>()
        var routeDistance = 0.0
        var previous: ProjectedRouteOccurrence? = null
        for (segment in route.segments) {
            val occurrence = when (val result = projectSegment(segment, topology, routeDistance)) {
                is SegmentProjection.Projected -> result.occurrence
                is SegmentProjection.Rejected -> return invalid(result.detail)
            }
            previous?.let { current ->
                when (val transition = validateTransition(current, occurrence, topology)) {
                    is TransitionResult.Rejected -> return invalid(transition.detail)
                    is TransitionResult.Turnout -> turnoutTraversals += transition.traversal
                    TransitionResult.Continuous -> Unit
                }
            }
            occurrences += occurrence
            previous = occurrence
            routeDistance = occurrence.routeEndMeters
        }
        return TopologyRouteProjectionResult.Projected(
            ProjectedTopologyRoute.create(
                topologyRevision = topology.revision,
                route = route,
                occurrences = occurrences,
                turnoutTraversals = turnoutTraversals
            )
        )
    }

    private fun projectSegment(
        segment: RouteSegmentSnapshot,
        topology: TopologyState,
        routeStartMeters: Double
    ): SegmentProjection {
        val projections = topology.railProjection[segment.railId]
            ?: return SegmentProjection.Rejected("route references missing rail ${segment.railId}")
        if (projections.size != 1) {
            return SegmentProjection.Rejected(
                "rail ${segment.railId} does not have one unambiguous topology projection"
            )
        }
        val projection = projections.single()
        val edge = topology.edges[projection.edgeId]
            ?: return SegmentProjection.Rejected(
                "rail ${segment.railId} references missing edge ${projection.edgeId}"
            )
        val railLength = abs(projection.railEndAbMeters - projection.railStartAbMeters)
        if (segment.endArcLengthMeters > railLength + Curve.TOLERANCE) {
            return SegmentProjection.Rejected("route segment exceeds rail ${segment.railId}")
        }
        val localStart = segment.startArcLengthMeters.coerceIn(0.0, railLength)
        val localEnd = segment.endArcLengthMeters.coerceIn(0.0, railLength)
        val canonicalStart = segment.canonicalAb(localStart, railLength)
        val canonicalEnd = segment.canonicalAb(localEnd, railLength)
        val edgeStart = projection.edgeDistanceAt(canonicalStart)
        val edgeEnd = projection.edgeDistanceAt(canonicalEnd)
        val railRunsFirstToSecond = projection.railEndAbMeters > projection.railStartAbMeters
        val segmentRunsCanonicalAb = segment.facing == ViewDirection.AB
        val edgeDirection = if (railRunsFirstToSecond == segmentRunsCanonicalAb) {
            ProjectedEdgeDirection.FIRST_TO_SECOND
        } else {
            ProjectedEdgeDirection.SECOND_TO_FIRST
        }
        check(edgeStart >= -Curve.TOLERANCE && edgeStart <= edge.lengthMeters + Curve.TOLERANCE)
        check(edgeEnd >= -Curve.TOLERANCE && edgeEnd <= edge.lengthMeters + Curve.TOLERANCE)
        return SegmentProjection.Projected(
            ProjectedRouteOccurrence(
                segment = segment,
                edgeId = edge.id,
                edgeDirection = edgeDirection,
                edgeStartMeters = edgeStart.coerceIn(0.0, edge.lengthMeters),
                edgeEndMeters = edgeEnd.coerceIn(0.0, edge.lengthMeters),
                railLengthMeters = railLength,
                canonicalStartAbMeters = canonicalStart,
                canonicalEndAbMeters = canonicalEnd,
                routeStartMeters = routeStartMeters,
                routeEndMeters = routeStartMeters + segment.lengthMeters
            )
        )
    }

    private fun validateTransition(
        current: ProjectedRouteOccurrence,
        next: ProjectedRouteOccurrence,
        topology: TopologyState
    ): TransitionResult {
        if (current.edgeId == next.edgeId &&
            current.edgeDirection == next.edgeDirection &&
            samePosition(current.edgeEndMeters, next.edgeStartMeters)
        ) {
            return TransitionResult.Continuous
        }

        val currentEdge = topology.edges.getValue(current.edgeId)
        val nextEdge = topology.edges.getValue(next.edgeId)
        val arrival = currentEdge.arrivalEnd(current) ?: return TransitionResult.Rejected(
            "route transition ${current.segment.railId} -> ${next.segment.railId} " +
                "leaves the current edge before its endpoint"
        )
        val departure = nextEdge.departureEnd(next) ?: return TransitionResult.Rejected(
            "route transition ${current.segment.railId} -> ${next.segment.railId} " +
                "enters the next edge after its endpoint"
        )
        if (arrival.nodeId != departure.nodeId) {
            return TransitionResult.Rejected(
                "route edges ${current.edgeId} and ${next.edgeId} do not share a structural node"
            )
        }
        return when (val node = topology.nodes.getValue(arrival.nodeId)) {
            is TopologyNode.Terminus -> TransitionResult.Rejected(
                "route continues through terminus ${node.id}"
            )
            is TopologyNode.LoopAnchor -> {
                if (arrival.port == departure.port ||
                    arrival.port !in node.ports || departure.port !in node.ports
                ) {
                    TransitionResult.Rejected("route uses an illegal continuation through loop anchor ${node.id}")
                } else {
                    TransitionResult.Continuous
                }
            }
            is TopologyNode.Turnout -> {
                if (departure.port !in node.legalContinuations.getValue(arrival.port)) {
                    TransitionResult.Rejected(
                        "route uses illegal turnout continuation ${arrival.port} -> ${departure.port}"
                    )
                } else {
                    TransitionResult.Turnout(
                        ProjectedTurnoutTraversal(
                            nodeId = node.id,
                            fromPort = arrival.port,
                            toPort = departure.port,
                            routeDistanceMeters = current.routeEndMeters
                        )
                    )
                }
            }
        }
    }

    private fun TrackEdge.arrivalEnd(occurrence: ProjectedRouteOccurrence): TopologyEdgeEnd? =
        when (occurrence.edgeDirection) {
            ProjectedEdgeDirection.FIRST_TO_SECOND -> second.takeIf {
                samePosition(occurrence.edgeEndMeters, lengthMeters)
            }
            ProjectedEdgeDirection.SECOND_TO_FIRST -> first.takeIf {
                samePosition(occurrence.edgeEndMeters, 0.0)
            }
        }

    private fun TrackEdge.departureEnd(occurrence: ProjectedRouteOccurrence): TopologyEdgeEnd? =
        when (occurrence.edgeDirection) {
            ProjectedEdgeDirection.FIRST_TO_SECOND -> first.takeIf {
                samePosition(occurrence.edgeStartMeters, 0.0)
            }
            ProjectedEdgeDirection.SECOND_TO_FIRST -> second.takeIf {
                samePosition(occurrence.edgeStartMeters, lengthMeters)
            }
        }

    private fun RouteSegmentSnapshot.canonicalAb(localMeters: Double, railLengthMeters: Double): Double =
        when (facing) {
            ViewDirection.AB -> localMeters
            ViewDirection.BA -> railLengthMeters - localMeters
        }

    private fun TrackEdgeSlice.edgeDistanceAt(canonicalAbMeters: Double): Double =
        if (railEndAbMeters > railStartAbMeters) {
            edgeStartMeters + canonicalAbMeters - railStartAbMeters
        } else {
            edgeStartMeters + railStartAbMeters - canonicalAbMeters
        }

    private fun samePosition(first: Double, second: Double): Boolean =
        abs(first - second) <= Curve.TOLERANCE

    private fun invalid(detail: String): TopologyRouteProjectionResult.Rejected = rejected(
        TopologyRouteProjectionRejection.INVALID_ROUTE,
        detail
    )

    private fun rejected(
        reason: TopologyRouteProjectionRejection,
        detail: String
    ) = TopologyRouteProjectionResult.Rejected(reason, detail)

    private sealed interface SegmentProjection {
        data class Projected(val occurrence: ProjectedRouteOccurrence) : SegmentProjection
        data class Rejected(val detail: String) : SegmentProjection
    }

    private sealed interface TransitionResult {
        data object Continuous : TransitionResult
        data class Turnout(val traversal: ProjectedTurnoutTraversal) : TransitionResult
        data class Rejected(val detail: String) : TransitionResult
    }
}
