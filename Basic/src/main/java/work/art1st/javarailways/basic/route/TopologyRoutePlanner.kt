package work.art1st.javarailways.basic.route

import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.route.planning.DirectedRailSpan
import work.art1st.javarailways.basic.api.route.planning.PhysicalTraversalPath
import work.art1st.javarailways.basic.api.route.planning.RoutePlanningRestrictions
import work.art1st.javarailways.basic.infrastructure.TopologyRouteProjectionResult
import work.art1st.javarailways.basic.infrastructure.TopologyRouteProjector
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyEdgeEnd
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNode
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNodeId
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.api.physics.rail.topology.TrackEdge
import work.art1st.javarailways.core.api.physics.rail.topology.TrackEdgeId
import work.art1st.javarailways.core.api.physics.rail.topology.TrackEdgeSlice
import java.util.PriorityQueue
import java.util.UUID
import kotlin.math.abs

/** One route endpoint in canonical rail A-to-B metres. */
data class OperationalRouteLocation(
    val railId: UUID,
    val arcLengthInAbMeters: Double
) {
    init {
        require(arcLengthInAbMeters.isFinite()) { "route location must be finite" }
    }
}

data class TopologyRoutePlanningInput(
    val topology: TopologyState,
    val restrictions: RoutePlanningRestrictions,
    val start: OperationalRouteLocation,
    val end: OperationalRouteLocation,
    val routeId: String,
    val routeRevision: Long,
    /** Restricts the first traversal to the authoritative physical facing when supplied. */
    val startFacing: ViewDirection? = null
) {
    init {
        require(routeId.isNotBlank()) { "routeId must not be blank" }
        require(routeRevision >= 0L) { "routeRevision must be non-negative" }
    }
}

enum class TopologyRoutePlanningRejection {
    STALE_RESTRICTIONS,
    INVALID_RESTRICTIONS,
    INVALID_LOCATION,
    UNREACHABLE
}

sealed interface TopologyRoutePlanningResult {
    data class Planned(val route: RouteSnapshot) : TopologyRoutePlanningResult

    data class Rejected(
        val reason: TopologyRoutePlanningRejection,
        val detail: String
    ) : TopologyRoutePlanningResult {
        init {
            require(detail.isNotBlank())
        }
    }
}

/**
 * Pure shortest-route selector over one immutable normalized Core topology.
 *
 * Restrictions are compiled once into a query-local graph before Dijkstra runs. Path replacements
 * become atomic graph arcs, so the search cannot enter or leave a permitted path in its interior.
 * The planner never reads live rails, invokes plugin code during expansion or mutates topology.
 */
object TopologyRoutePlanner {
    fun isValidLocation(topology: TopologyState, location: OperationalRouteLocation): Boolean =
        resolveLocation(location, topology) != null

    fun plan(input: TopologyRoutePlanningInput): TopologyRoutePlanningResult {
        val topology = input.topology
        if (input.restrictions.topologyRevision != topology.revision) {
            return rejected(
                TopologyRoutePlanningRejection.STALE_RESTRICTIONS,
                "restriction topology revision ${input.restrictions.topologyRevision} does not match ${topology.revision}"
            )
        }
        val start = resolveLocation(input.start, topology)
            ?: return invalidLocation(input.start)
        val end = resolveLocation(input.end, topology)
            ?: return invalidLocation(input.end)
        if (input.start.railId == input.end.railId &&
            samePosition(input.start.arcLengthInAbMeters, input.end.arcLengthInAbMeters)
        ) {
            return rejected(
                TopologyRoutePlanningRejection.INVALID_LOCATION,
                "route start and end are the same physical location"
            )
        }
        val compiled = when (val result = RestrictionCompiler.compile(topology, input.restrictions)) {
            is RestrictionCompilation.Compiled -> result.graph
            is RestrictionCompilation.Rejected -> return rejected(
                TopologyRoutePlanningRejection.INVALID_RESTRICTIONS,
                result.detail
            )
        }
        val graph = QueryGraph(topology, compiled, start, end)
        val startDirections = input.startFacing?.let { facing -> start.edgeDirection(facing) }?.let(::listOf)
            ?: EdgeDirection.entries
        val path = Search(graph, end).run(
            startDirections.map { direction -> OrientedPoint(start.edgeId, start.edgeDistanceMeters, direction) }
                .filterNot(graph::isForbidden)
        ) ?: return rejected(
            TopologyRoutePlanningRejection.UNREACHABLE,
            "no route satisfies topology and route-planning restrictions"
        )

        val plannedSegments = path.arcs.flatMap(GraphArc::segments).toMutableList()
        input.startFacing?.let { facing -> start.edgeDirection(facing) }
            ?.let { direction -> start.marker(direction) }?.let { marker ->
            if (plannedSegments.firstOrNull()?.startsAt(marker) != true) plannedSegments.add(0, marker)
        }
        val goalMarker = end.marker(path.goal.direction)
        if (plannedSegments.lastOrNull()?.endsAt(goalMarker) != true) plannedSegments += goalMarker
        if (plannedSegments.sumOf(RouteSegmentSnapshot::lengthMeters) <= POSITION_EPSILON) {
            return rejected(
                TopologyRoutePlanningRejection.INVALID_LOCATION,
                "route start and end resolve to the same physical position"
            )
        }
        return TopologyRoutePlanningResult.Planned(
            RouteSnapshot(
                routeId = input.routeId,
                revision = input.routeRevision,
                topologyRevision = topology.revision,
                segments = plannedSegments
            )
        )
    }

    private class Search(
        private val graph: QueryGraph,
        private val goal: ResolvedLocation
    ) {
        private val distances = hashMapOf<OrientedPoint, Double>()
        private val predecessors = hashMapOf<OrientedPoint, Predecessor>()
        private val pending = PriorityQueue<QueueEntry>(compareBy(QueueEntry::distance, QueueEntry::sequence))
        private var sequence = 0L

        fun run(starts: Collection<OrientedPoint>): SearchPath? {
            starts.forEach { start -> offer(start, 0.0, null) }
            while (pending.isNotEmpty()) {
                val entry = pending.remove()
                if (entry.distance > distances.getValue(entry.point)) continue
                if (entry.point.matches(goal) && !graph.isForbidden(entry.point)) return rebuild(entry.point)
                graph.next(entry.point).forEach { arc ->
                    offer(arc.target, entry.distance + arc.costMeters, Predecessor(entry.point, arc))
                }
            }
            return null
        }

        private fun offer(point: OrientedPoint, distance: Double, predecessor: Predecessor?) {
            val previous = distances[point]
            if (previous != null && distance >= previous) return
            distances[point] = distance
            if (predecessor != null) predecessors[point] = predecessor
            pending += QueueEntry(point, distance, sequence++)
        }

        private fun rebuild(goal: OrientedPoint): SearchPath {
            val reversed = ArrayList<GraphArc>()
            var point = goal
            while (true) {
                val predecessor = predecessors[point] ?: break
                reversed += predecessor.arc
                point = predecessor.previous
            }
            reversed.reverse()
            return SearchPath(reversed, goal)
        }
    }

    private class QueryGraph(
        private val topology: TopologyState,
        private val restrictions: CompiledRestrictions,
        start: ResolvedLocation,
        end: ResolvedLocation
    ) {
        private val cutsByEdge: Map<TrackEdgeId, List<Double>>
        private val incidentEnds: Map<TopologyNodeId, List<IncidentEnd>> = buildIncidentEnds(topology)

        init {
            val cuts = topology.edges.values.associate { edge ->
                edge.id to linkedSetOf(0.0, edge.lengthMeters)
            }.toMutableMap()
            restrictions.cutPoints.forEach { point -> cuts.getValue(point.edgeId) += point.distanceMeters }
            cuts.getValue(start.edgeId) += start.edgeDistanceMeters
            cuts.getValue(end.edgeId) += end.edgeDistanceMeters
            cutsByEdge = cuts.mapValues { (_, values) -> values.sorted() }
        }

        fun next(point: OrientedPoint): List<GraphArc> = buildList {
            baseArc(point)?.let(::add)
            addAll(restrictions.macrosByStart[point].orEmpty())
            structuralArcs(point).forEach(::add)
        }

        fun isForbidden(point: OrientedPoint): Boolean =
            restrictions.forbidden[DirectedEdge(point.edgeId, point.direction)].orEmpty()
                .any { interval ->
                    point.distanceMeters >= interval.lower - POSITION_EPSILON &&
                        point.distanceMeters <= interval.upper + POSITION_EPSILON
                }

        private fun baseArc(point: OrientedPoint): GraphArc? {
            val cuts = cutsByEdge.getValue(point.edgeId)
            val index = cuts.binarySearch(point.distanceMeters)
            check(index >= 0) { "query point is absent from its edge cut set" }
            val targetIndex = when (point.direction) {
                EdgeDirection.FIRST_TO_SECOND -> index + 1
                EdgeDirection.SECOND_TO_FIRST -> index - 1
            }
            if (targetIndex !in cuts.indices) return null
            val targetDistance = cuts[targetIndex]
            val interval = Interval(
                minOf(point.distanceMeters, targetDistance),
                maxOf(point.distanceMeters, targetDistance)
            )
            val key = DirectedEdge(point.edgeId, point.direction)
            if (restrictions.suppressed[key].orEmpty().any { it.overlaps(interval) }) return null
            val edge = topology.edges.getValue(point.edgeId)
            return GraphArc(
                target = OrientedPoint(point.edgeId, targetDistance, point.direction),
                costMeters = interval.lengthMeters,
                segments = EdgeTraversal(
                    point.edgeId,
                    point.direction,
                    point.distanceMeters,
                    targetDistance
                ).toSegments(edge)
            )
        }

        private fun structuralArcs(point: OrientedPoint): List<GraphArc> {
            val edge = topology.edges.getValue(point.edgeId)
            val arrival = edge.arrivalAt(point) ?: return emptyList()
            val node = topology.nodes.getValue(arrival.nodeId)
            return incidentEnds.getValue(arrival.nodeId).mapNotNull { departure ->
                if (!allowsContinuation(node, arrival.port, departure.end.port)) return@mapNotNull null
                GraphArc(
                    target = OrientedPoint(
                        departure.edge.id,
                        departure.startDistanceMeters,
                        departure.direction
                    ),
                    costMeters = 0.0,
                    segments = emptyList()
                )
            }
        }
    }

    private object RestrictionCompiler {
        fun compile(
            topology: TopologyState,
            restrictions: RoutePlanningRestrictions
        ): RestrictionCompilation {
            val suppressed = linkedMapOf<DirectedEdge, MutableList<Interval>>()
            val forbidden = linkedMapOf<DirectedEdge, MutableList<Interval>>()
            val cuts = linkedSetOf<UnorientedPoint>()
            val macroArcs = linkedMapOf<OrientedPoint, MutableList<GraphArc>>()
            val replacementOwners = linkedMapOf<DirectedEdge, MutableList<Pair<Interval, Int>>>()
            val forbiddenByRail = mergeRailSpans(restrictions.forbiddenTraversals)

            restrictions.forbiddenTraversals.forEachIndexed { index, span ->
                val projected = project(span, topology)
                    ?: return rejected("forbidden traversal $index references a missing rail or invalid interval")
                suppress(projected, suppressed, cuts)
                forbidden.getOrPut(projected.edge) { mutableListOf() } += projected.interval
            }

            restrictions.pathReplacements.forEachIndexed { replacementIndex, replacement ->
                val coverage = mergeRailSpans(replacement.replacedTraversals)
                for ((spanIndex, span) in replacement.replacedTraversals.withIndex()) {
                    val projected = project(span, topology)
                        ?: return rejected(
                            "path replacement $replacementIndex traversal $spanIndex references a missing rail or invalid interval"
                        )
                    replacementOwners.getOrPut(projected.edge) { mutableListOf() } +=
                        projected.interval to replacementIndex
                    suppress(projected, suppressed, cuts)
                }

                replacement.permittedPaths.forEachIndexed pathLoop@{ pathIndex, path ->
                    val uncovered = path.spans.firstOrNull { span -> !coverage.covers(span) }
                    if (uncovered != null) {
                        return rejected(
                            "path replacement $replacementIndex path $pathIndex leaves its replaced traversals"
                        )
                    }
                    val routeSegments = path.spans.map { span ->
                        span.toRouteSegment(topology)
                            ?: return rejected(
                                "path replacement $replacementIndex path $pathIndex references a missing rail or invalid interval"
                            )
                    }
                    val route = RouteSnapshot(
                        routeId = "route-planning:replacement-$replacementIndex-$pathIndex",
                        revision = 0L,
                        topologyRevision = topology.revision,
                        segments = routeSegments
                    )
                    when (val projectedRoute = TopologyRouteProjector.project(route, topology)) {
                        is TopologyRouteProjectionResult.Rejected -> return rejected(
                            "path replacement $replacementIndex path $pathIndex is not physically continuous: " +
                                projectedRoute.detail
                        )
                        is TopologyRouteProjectionResult.Projected -> Unit
                    }
                    if (path.spans.any(forbiddenByRail::overlaps)) return@pathLoop

                    val first = project(path.spans.first(), topology)!!
                    val last = project(path.spans.last(), topology)!!
                    val start = OrientedPoint(first.edge.edgeId, first.startDistanceMeters, first.edge.direction)
                    val target = OrientedPoint(last.edge.edgeId, last.endDistanceMeters, last.edge.direction)
                    cuts += start.unoriented()
                    cuts += target.unoriented()
                    macroArcs.getOrPut(start) { mutableListOf() } += GraphArc(
                        target = target,
                        costMeters = path.spans.sumOf(DirectedRailSpan::lengthMeters),
                        segments = routeSegments
                    )
                }
            }

            for ((edge, ownedIntervals) in replacementOwners) {
                val normalized = ownedIntervals.groupBy(Pair<Interval, Int>::second)
                    .flatMap { (owner, intervals) ->
                        mergeIntervals(intervals.map(Pair<Interval, Int>::first)).map { it to owner }
                    }
                    .sortedBy { (interval, _) -> interval.lower }
                var active: Pair<Interval, Int>? = null
                for (candidate in normalized) {
                    val current = active
                    if (current != null && current.second != candidate.second &&
                        current.first.overlaps(candidate.first)
                    ) {
                        return rejected(
                            "path replacements overlap on topology edge ${edge.edgeId} in ${edge.direction}"
                        )
                    }
                    if (current == null || candidate.first.upper > current.first.upper) active = candidate
                }
            }

            val sortedMacros = macroArcs.mapValues { (_, arcs) ->
                arcs.sortedBy { arc -> arc.segments.joinToString("|") { "${it.railId}:${it.facing}:${it.startArcLengthMeters}" } }
            }
            return RestrictionCompilation.Compiled(
                CompiledRestrictions(
                    suppressed = suppressed.mapValues { (_, intervals) -> mergeIntervals(intervals) },
                    forbidden = forbidden.mapValues { (_, intervals) -> mergeIntervals(intervals) },
                    cutPoints = cuts,
                    macrosByStart = sortedMacros
                )
            )
        }

        private fun suppress(
            span: ProjectedSpan,
            suppressed: MutableMap<DirectedEdge, MutableList<Interval>>,
            cuts: MutableSet<UnorientedPoint>
        ) {
            suppressed.getOrPut(span.edge) { mutableListOf() } += span.interval
            cuts += UnorientedPoint(span.edge.edgeId, span.interval.lower)
            cuts += UnorientedPoint(span.edge.edgeId, span.interval.upper)
        }

        private fun rejected(detail: String) = RestrictionCompilation.Rejected(detail)
    }

    private fun project(span: DirectedRailSpan, topology: TopologyState): ProjectedSpan? {
        val projection = topology.railProjection[span.railId]?.singleOrNull() ?: return null
        val railLength = abs(projection.railEndAbMeters - projection.railStartAbMeters)
        if (span.upperAbMeters > railLength + POSITION_EPSILON) return null
        val canonicalStart = if (span.facing == ViewDirection.AB) span.lowerAbMeters else span.upperAbMeters
        val canonicalEnd = if (span.facing == ViewDirection.AB) span.upperAbMeters else span.lowerAbMeters
        val start = projection.edgeDistanceAt(canonicalStart.coerceIn(0.0, railLength))
        val end = projection.edgeDistanceAt(canonicalEnd.coerceIn(0.0, railLength))
        val direction = if (end > start) EdgeDirection.FIRST_TO_SECOND else EdgeDirection.SECOND_TO_FIRST
        return ProjectedSpan(
            edge = DirectedEdge(projection.edgeId, direction),
            interval = Interval(minOf(start, end), maxOf(start, end)),
            startDistanceMeters = start,
            endDistanceMeters = end
        )
    }

    private fun DirectedRailSpan.toRouteSegment(topology: TopologyState): RouteSegmentSnapshot? {
        val projection = topology.railProjection[railId]?.singleOrNull() ?: return null
        val railLength = abs(projection.railEndAbMeters - projection.railStartAbMeters)
        if (upperAbMeters > railLength + POSITION_EPSILON) return null
        return when (facing) {
            ViewDirection.AB -> RouteSegmentSnapshot(railId, facing, lowerAbMeters, upperAbMeters)
            ViewDirection.BA -> RouteSegmentSnapshot(
                railId,
                facing,
                railLength - upperAbMeters,
                railLength - lowerAbMeters
            )
        }
    }

    private fun mergeRailSpans(spans: Collection<DirectedRailSpan>): RailSpanIndex = RailSpanIndex(
        spans.groupBy { RailDirection(it.railId, it.facing) }
            .mapValues { (_, values) ->
                mergeIntervals(values.map { Interval(it.lowerAbMeters, it.upperAbMeters) })
            }
    )

    private fun mergeIntervals(intervals: Collection<Interval>): List<Interval> {
        if (intervals.isEmpty()) return emptyList()
        val sorted = intervals.sortedBy(Interval::lower)
        val result = ArrayList<Interval>()
        var current = sorted.first()
        for (next in sorted.drop(1)) {
            if (next.lower <= current.upper + POSITION_EPSILON) {
                current = Interval(current.lower, maxOf(current.upper, next.upper))
            } else {
                result += current
                current = next
            }
        }
        result += current
        return result
    }

    private fun resolveLocation(
        location: OperationalRouteLocation,
        topology: TopologyState
    ): ResolvedLocation? {
        val projection = topology.railProjection[location.railId]?.singleOrNull() ?: return null
        val railLength = abs(projection.railEndAbMeters - projection.railStartAbMeters)
        if (location.arcLengthInAbMeters < 0.0 || location.arcLengthInAbMeters > railLength) return null
        val edge = topology.edges[projection.edgeId] ?: return null
        val edgeDistance = projection.edgeDistanceAt(location.arcLengthInAbMeters)
        if (edgeDistance < 0.0 || edgeDistance > edge.lengthMeters) return null
        return ResolvedLocation(
            railId = location.railId,
            canonicalAbMeters = location.arcLengthInAbMeters,
            railLengthMeters = railLength,
            railRunsFirstToSecond = projection.railEndAbMeters > projection.railStartAbMeters,
            edgeId = edge.id,
            edgeDistanceMeters = edgeDistance
        )
    }

    private fun buildIncidentEnds(topology: TopologyState): Map<TopologyNodeId, List<IncidentEnd>> {
        val result = topology.nodes.keys.associateWithTo(linkedMapOf()) { mutableListOf<IncidentEnd>() }
        topology.edges.values.sortedBy { it.id.index }.forEach { edge ->
            result.getValue(edge.first.nodeId) += IncidentEnd(edge, edge.first, EdgeDirection.FIRST_TO_SECOND, 0.0)
            result.getValue(edge.second.nodeId) += IncidentEnd(
                edge,
                edge.second,
                EdgeDirection.SECOND_TO_FIRST,
                edge.lengthMeters
            )
        }
        return result.mapValues { (_, ends) -> ends.toList() }
    }

    private fun allowsContinuation(
        node: TopologyNode,
        arrivalPort: RailEndpointRef,
        departurePort: RailEndpointRef
    ): Boolean = when (node) {
        is TopologyNode.Terminus -> false
        is TopologyNode.LoopAnchor ->
            arrivalPort != departurePort && arrivalPort in node.ports && departurePort in node.ports
        is TopologyNode.Turnout -> departurePort in node.legalContinuations.getValue(arrivalPort)
    }

    private fun EdgeTraversal.toSegments(edge: TrackEdge): List<RouteSegmentSnapshot> {
        val lower = minOf(startDistanceMeters, endDistanceMeters)
        val upper = maxOf(startDistanceMeters, endDistanceMeters)
        val slices = edge.railSlices.withEdgeOffsets()
        val ordered = if (direction == EdgeDirection.FIRST_TO_SECOND) slices else slices.asReversed()
        return ordered.mapNotNull { slice ->
            val overlapStart = maxOf(lower, slice.edgeStartMeters)
            val overlapEnd = minOf(upper, slice.edgeEndMeters)
            if (overlapEnd <= overlapStart) return@mapNotNull null
            val canonicalAtLower = slice.canonicalAbAt(overlapStart)
            val canonicalAtUpper = slice.canonicalAbAt(overlapEnd)
            val canonicalStart = if (direction == EdgeDirection.FIRST_TO_SECOND) {
                canonicalAtLower
            } else {
                canonicalAtUpper
            }
            val canonicalEnd = if (direction == EdgeDirection.FIRST_TO_SECOND) {
                canonicalAtUpper
            } else {
                canonicalAtLower
            }
            val facing = if (canonicalEnd > canonicalStart) ViewDirection.AB else ViewDirection.BA
            val railLength = abs(slice.railEndAbMeters - slice.railStartAbMeters)
            val localStart = if (facing == ViewDirection.AB) canonicalStart else railLength - canonicalStart
            val localEnd = if (facing == ViewDirection.AB) canonicalEnd else railLength - canonicalEnd
            RouteSegmentSnapshot(slice.railId, facing, localStart, localEnd)
        }
    }

    private fun List<work.art1st.javarailways.core.api.physics.rail.topology.RailSliceRef>.withEdgeOffsets():
        List<SliceWithOffset> {
        var offset = 0.0
        return map { slice ->
            SliceWithOffset(
                railId = slice.railId,
                railStartAbMeters = slice.startAbMeters,
                railEndAbMeters = slice.endAbMeters,
                edgeStartMeters = offset,
                edgeEndMeters = offset + slice.lengthMeters
            ).also { offset = it.edgeEndMeters }
        }
    }

    private fun TrackEdge.arrivalAt(point: OrientedPoint): TopologyEdgeEnd? = when (point.direction) {
        EdgeDirection.FIRST_TO_SECOND -> second.takeIf { samePosition(point.distanceMeters, lengthMeters) }
        EdgeDirection.SECOND_TO_FIRST -> first.takeIf { samePosition(point.distanceMeters, 0.0) }
    }

    private fun TrackEdgeSlice.edgeDistanceAt(canonicalAbMeters: Double): Double =
        if (railEndAbMeters > railStartAbMeters) {
            edgeStartMeters + canonicalAbMeters - railStartAbMeters
        } else {
            edgeStartMeters + railStartAbMeters - canonicalAbMeters
        }

    private fun SliceWithOffset.canonicalAbAt(edgeDistance: Double): Double {
        val localDistance = edgeDistance - edgeStartMeters
        return if (railEndAbMeters > railStartAbMeters) {
            railStartAbMeters + localDistance
        } else {
            railStartAbMeters - localDistance
        }
    }

    private fun ResolvedLocation.edgeDirection(facing: ViewDirection): EdgeDirection {
        val runsFirstToSecond = if (facing == ViewDirection.AB) railRunsFirstToSecond else !railRunsFirstToSecond
        return if (runsFirstToSecond) EdgeDirection.FIRST_TO_SECOND else EdgeDirection.SECOND_TO_FIRST
    }

    private fun ResolvedLocation.marker(direction: EdgeDirection): RouteSegmentSnapshot {
        val traversalRunsFirstToSecond = direction == EdgeDirection.FIRST_TO_SECOND
        val facing = if (railRunsFirstToSecond == traversalRunsFirstToSecond) {
            ViewDirection.AB
        } else {
            ViewDirection.BA
        }
        val local = if (facing == ViewDirection.AB) canonicalAbMeters else railLengthMeters - canonicalAbMeters
        return RouteSegmentSnapshot(railId, facing, local, local)
    }

    private fun RouteSegmentSnapshot.startsAt(marker: RouteSegmentSnapshot): Boolean =
        railId == marker.railId && facing == marker.facing &&
            samePosition(startArcLengthMeters, marker.startArcLengthMeters)

    private fun RouteSegmentSnapshot.endsAt(marker: RouteSegmentSnapshot): Boolean =
        railId == marker.railId && facing == marker.facing &&
            samePosition(endArcLengthMeters, marker.endArcLengthMeters)

    private fun OrientedPoint.matches(location: ResolvedLocation): Boolean =
        edgeId == location.edgeId && samePosition(distanceMeters, location.edgeDistanceMeters)

    private fun OrientedPoint.unoriented() = UnorientedPoint(edgeId, distanceMeters)

    private fun samePosition(first: Double, second: Double): Boolean = abs(first - second) <= POSITION_EPSILON

    private fun invalidLocation(location: OperationalRouteLocation) = rejected(
        TopologyRoutePlanningRejection.INVALID_LOCATION,
        "invalid route location ${location.railId}@${location.arcLengthInAbMeters}"
    )

    private fun rejected(reason: TopologyRoutePlanningRejection, detail: String) =
        TopologyRoutePlanningResult.Rejected(reason, detail)

    private const val POSITION_EPSILON = 1e-9

    private enum class EdgeDirection { FIRST_TO_SECOND, SECOND_TO_FIRST }

    private data class ResolvedLocation(
        val railId: UUID,
        val canonicalAbMeters: Double,
        val railLengthMeters: Double,
        val railRunsFirstToSecond: Boolean,
        val edgeId: TrackEdgeId,
        val edgeDistanceMeters: Double
    )

    private data class DirectedEdge(val edgeId: TrackEdgeId, val direction: EdgeDirection)
    private data class UnorientedPoint(val edgeId: TrackEdgeId, val distanceMeters: Double)
    private data class OrientedPoint(
        val edgeId: TrackEdgeId,
        val distanceMeters: Double,
        val direction: EdgeDirection
    )

    private data class Interval(val lower: Double, val upper: Double) {
        val lengthMeters: Double get() = upper - lower
        fun overlaps(other: Interval): Boolean =
            minOf(upper, other.upper) - maxOf(lower, other.lower) > POSITION_EPSILON
        fun covers(other: Interval): Boolean =
            lower <= other.lower + POSITION_EPSILON && upper >= other.upper - POSITION_EPSILON
    }

    private data class RailDirection(val railId: UUID, val facing: ViewDirection)

    private class RailSpanIndex(private val intervals: Map<RailDirection, List<Interval>>) {
        fun covers(span: DirectedRailSpan): Boolean {
            val values = intervals[RailDirection(span.railId, span.facing)].orEmpty()
            val target = Interval(span.lowerAbMeters, span.upperAbMeters)
            val index = insertionPoint(values, target.lower)
            return index > 0 && values[index - 1].covers(target)
        }

        fun overlaps(span: DirectedRailSpan): Boolean {
            val values = intervals[RailDirection(span.railId, span.facing)].orEmpty()
            val target = Interval(span.lowerAbMeters, span.upperAbMeters)
            val index = insertionPoint(values, target.lower)
            return (index > 0 && values[index - 1].overlaps(target)) ||
                (index < values.size && values[index].overlaps(target))
        }

        private fun insertionPoint(values: List<Interval>, lower: Double): Int {
            var low = 0
            var high = values.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (values[middle].lower <= lower) low = middle + 1 else high = middle
            }
            return low
        }
    }

    private data class ProjectedSpan(
        val edge: DirectedEdge,
        val interval: Interval,
        val startDistanceMeters: Double,
        val endDistanceMeters: Double
    )

    private data class CompiledRestrictions(
        val suppressed: Map<DirectedEdge, List<Interval>>,
        val forbidden: Map<DirectedEdge, List<Interval>>,
        val cutPoints: Set<UnorientedPoint>,
        val macrosByStart: Map<OrientedPoint, List<GraphArc>>
    )

    private sealed interface RestrictionCompilation {
        data class Compiled(val graph: CompiledRestrictions) : RestrictionCompilation
        data class Rejected(val detail: String) : RestrictionCompilation
    }

    private data class GraphArc(
        val target: OrientedPoint,
        val costMeters: Double,
        val segments: List<RouteSegmentSnapshot>
    )

    private data class EdgeTraversal(
        val edgeId: TrackEdgeId,
        val direction: EdgeDirection,
        val startDistanceMeters: Double,
        val endDistanceMeters: Double
    )

    private data class IncidentEnd(
        val edge: TrackEdge,
        val end: TopologyEdgeEnd,
        val direction: EdgeDirection,
        val startDistanceMeters: Double
    )

    private data class QueueEntry(val point: OrientedPoint, val distance: Double, val sequence: Long)
    private data class Predecessor(val previous: OrientedPoint, val arc: GraphArc)
    private data class SearchPath(val arcs: List<GraphArc>, val goal: OrientedPoint)

    private data class SliceWithOffset(
        val railId: UUID,
        val railStartAbMeters: Double,
        val railEndAbMeters: Double,
        val edgeStartMeters: Double,
        val edgeEndMeters: Double
    )
}
