package work.art1st.javarailways.simpleato.routing

import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.rail.RailInfrastructureMetadataSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyEdgeEnd
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNode
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNodeId
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.api.physics.rail.topology.TrackEdge
import work.art1st.javarailways.core.api.physics.rail.topology.TrackEdgeId
import work.art1st.javarailways.core.api.physics.rail.topology.TrackEdgeSlice
import java.util.Collections
import java.util.PriorityQueue
import java.util.UUID
import kotlin.math.abs

/** One operational endpoint in canonical rail A-to-B metres. */
internal data class OperationalRouteLocation(
    val railId: UUID,
    val arcLengthInAbMeters: Double
) {
    init {
        require(arcLengthInAbMeters.isFinite()) { "route location must be finite" }
    }
}

/** Immutable operating-direction overlay resolved for exactly one topology revision. */
internal class OperatingDirectionOverlay(
    val topologyRevision: Long,
    val revision: Long,
    allowedDirections: Map<UUID, Set<ViewDirection>>
) {
    val allowedDirections: Map<UUID, Set<ViewDirection>> = immutableDirectionMap(allowedDirections)

    init {
        require(topologyRevision >= 0L) { "topology revision must be non-negative" }
        require(revision >= 0L) { "operating-direction revision must be non-negative" }
    }

    fun allows(railId: UUID, direction: ViewDirection): Boolean =
        allowedDirections[railId]?.contains(direction) ?: true

    companion object {
        private fun immutableDirectionMap(source: Map<UUID, Set<ViewDirection>>): Map<UUID, Set<ViewDirection>> =
            Collections.unmodifiableMap(
                source.mapValuesTo(linkedMapOf()) { (_, directions) ->
                    Collections.unmodifiableSet(directions.toSet())
                }
            )

        /** Projects one atomic metadata snapshot without retaining the mutable Basic store. */
        fun from(
            topology: TopologyState,
            metadata: RailInfrastructureMetadataSnapshot
        ): OperatingDirectionOverlay = OperatingDirectionOverlay(
            topologyRevision = topology.revision,
            revision = metadata.revision,
            allowedDirections = metadata.values.mapNotNull { (railId, value) ->
                value.operating.allowedDirection?.let { railId to setOf(it) }
            }.toMap()
        )
    }
}

internal data class TopologyRoutePlanningInput(
    val topology: TopologyState,
    val operatingDirections: OperatingDirectionOverlay,
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

internal enum class TopologyRoutePlanningRejection {
    STALE_OPERATING_DIRECTIONS,
    INVALID_OPERATING_DIRECTIONS,
    INVALID_LOCATION,
    UNREACHABLE
}

internal sealed interface TopologyRoutePlanningResult {
    data class Planned(
        val route: RouteSnapshot,
        val operatingDirectionRevision: Long
    ) : TopologyRoutePlanningResult

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
 * Pure operational shortest-route selector over one immutable normalized Core topology.
 *
 * Query endpoints split their containing edges only in the local search. The planner never reads
 * live rails or turnout selections, never mutates [TopologyState], and never claims resources.
 */
internal object TopologyRoutePlanner {
    /** Performs the planner's exact endpoint validation without starting a graph search. */
    fun isValidLocation(topology: TopologyState, location: OperationalRouteLocation): Boolean =
        resolveLocation(location, topology) != null

    fun plan(input: TopologyRoutePlanningInput): TopologyRoutePlanningResult {
        val topology = input.topology
        val directions = input.operatingDirections
        if (directions.topologyRevision != topology.revision) {
            return rejected(
                TopologyRoutePlanningRejection.STALE_OPERATING_DIRECTIONS,
                "operating-direction topology revision ${directions.topologyRevision} does not match ${topology.revision}"
            )
        }
        val unknownDirectionRails = directions.allowedDirections.keys - topology.railProjection.keys
        if (unknownDirectionRails.isNotEmpty()) {
            return rejected(
                TopologyRoutePlanningRejection.INVALID_OPERATING_DIRECTIONS,
                "operating directions reference missing rails: ${unknownDirectionRails.sorted()}"
            )
        }

        val start = resolveLocation(input.start, topology)
            ?: return rejected(
                TopologyRoutePlanningRejection.INVALID_LOCATION,
                "invalid start location ${input.start.railId}@${input.start.arcLengthInAbMeters}"
            )
        val end = resolveLocation(input.end, topology)
            ?: return rejected(
                TopologyRoutePlanningRejection.INVALID_LOCATION,
                "invalid end location ${input.end.railId}@${input.end.arcLengthInAbMeters}"
            )
        if (input.start.railId == input.end.railId &&
            samePosition(input.start.arcLengthInAbMeters, input.end.arcLengthInAbMeters)
        ) {
            return rejected(
                TopologyRoutePlanningRejection.INVALID_LOCATION,
                "route start and end are the same physical location"
            )
        }
        if (input.startFacing != null && !directions.allows(start.railId, input.startFacing)) {
            return rejected(
                TopologyRoutePlanningRejection.UNREACHABLE,
                "authoritative start direction ${input.startFacing} is not allowed on rail ${start.railId}"
            )
        }

        val search = Search(topology, directions, end)
        search.seed(start, input.startFacing)
        val path = search.run()
            ?: return rejected(
                TopologyRoutePlanningRejection.UNREACHABLE,
                "no route satisfies topology and operating-direction constraints"
            )
        val plannedSegments = path.traversals.flatMap { traversal ->
            traversal.toSegments(topology.getEdge(traversal.edgeId))
        } + listOfNotNull(path.terminalMarker)
        val authoritativeStart = input.startFacing?.let(start::edgeDirection)?.let(start::endpointMarker)
        val segments = if (authoritativeStart != null &&
            plannedSegments.firstOrNull()?.startsAt(authoritativeStart) != true
        ) {
            listOf(authoritativeStart) + plannedSegments
        } else {
            plannedSegments
        }
        if (segments.sumOf(RouteSegmentSnapshot::lengthMeters) <= POSITION_EPSILON) {
            return rejected(
                TopologyRoutePlanningRejection.INVALID_LOCATION,
                "route start and end resolve to the same physical position"
            )
        }
        return TopologyRoutePlanningResult.Planned(
            route = RouteSnapshot(
                routeId = input.routeId,
                revision = input.routeRevision,
                topologyRevision = topology.revision,
                segments = segments
            ),
            operatingDirectionRevision = directions.revision
        )
    }

    private fun resolveLocation(
        location: OperationalRouteLocation,
        topology: TopologyState
    ): ResolvedLocation? {
        val projections = topology.railProjection[location.railId] ?: return null
        if (projections.size != 1) return null
        val projection = projections.single()
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

    private class Search(
        private val topology: TopologyState,
        private val directions: OperatingDirectionOverlay,
        private val goal: ResolvedLocation
    ) {
        private val incidentEnds: Map<TopologyNodeId, List<IncidentEnd>> = buildIncidentEnds(topology)
        private val distances = hashMapOf<ArrivalState, Double>()
        private val predecessors = hashMapOf<ArrivalState, Predecessor>()
        private val pending = PriorityQueue<QueueEntry>(
            compareBy(QueueEntry::distance, QueueEntry::sequence)
        )
        private var sequence = 0L
        private var bestGoal: GoalCandidate? = null

        fun seed(start: ResolvedLocation, startFacing: ViewDirection?) {
            val edge = topology.getEdge(start.edgeId)
            val directions = startFacing?.let(start::edgeDirection)?.let(::listOf)
                ?: EdgeDirection.entries
            directions.forEach { direction ->
                seedDirection(edge, direction, start.edgeDistanceMeters)
            }
        }

        fun run(): SearchPath? {
            while (pending.isNotEmpty()) {
                val entry = pending.remove()
                val goalDistance = bestGoal?.distance
                if (goalDistance != null && entry.distance > goalDistance) break
                if (entry.distance > distances.getValue(entry.state)) continue
                val node = topology.nodes.getValue(entry.state.nodeId)
                for (departure in incidentEnds.getValue(entry.state.nodeId)) {
                    if (!allowsContinuation(node, entry.state.arrivalPort, departure.end.port)) continue
                    expand(entry, departure)
                }
            }
            return bestGoal?.let(::rebuildPath)
        }

        private fun seedDirection(edge: TrackEdge, direction: EdgeDirection, startDistance: Double) {
            goalTraversal(edge, direction, startDistance)?.let { traversal ->
                if (allows(traversal)) considerGoal(null, traversal, null, traversal.lengthMeters)
            }
            val traversal = traversalToEnd(edge, direction, startDistance)
            if (traversal != null && !allows(traversal)) return
            val arrival = edge.arrival(direction)
            offer(
                state = ArrivalState(arrival.nodeId, arrival.port),
                distance = traversal?.lengthMeters ?: 0.0,
                predecessor = Predecessor(null, traversal)
            )
        }

        private fun expand(entry: QueueEntry, departure: IncidentEnd) {
            if (departure.edge.id == goal.edgeId &&
                samePosition(departure.startDistance, goal.edgeDistanceMeters)
            ) {
                val marker = goal.endpointMarker(departure.direction)
                if (directions.allows(marker.railId, marker.facing)) {
                    considerGoal(entry.state, null, marker, entry.distance)
                }
            }
            goalTraversal(departure.edge, departure.direction, departure.startDistance)?.let { traversal ->
                if (allows(traversal)) {
                    considerGoal(entry.state, traversal, null, entry.distance + traversal.lengthMeters)
                }
            }
            val traversal = traversalToEnd(departure.edge, departure.direction, departure.startDistance)
                ?: return
            if (!allows(traversal)) return
            val arrival = departure.edge.arrival(departure.direction)
            offer(
                state = ArrivalState(arrival.nodeId, arrival.port),
                distance = entry.distance + traversal.lengthMeters,
                predecessor = Predecessor(entry.state, traversal)
            )
        }

        private fun goalTraversal(
            edge: TrackEdge,
            direction: EdgeDirection,
            startDistance: Double
        ): EdgeTraversal? {
            if (edge.id != goal.edgeId) return null
            val ahead = when (direction) {
                EdgeDirection.FIRST_TO_SECOND -> goal.edgeDistanceMeters >= startDistance
                EdgeDirection.SECOND_TO_FIRST -> goal.edgeDistanceMeters <= startDistance
            }
            if (!ahead || samePosition(startDistance, goal.edgeDistanceMeters)) return null
            return EdgeTraversal(edge.id, direction, startDistance, goal.edgeDistanceMeters)
        }

        private fun traversalToEnd(
            edge: TrackEdge,
            direction: EdgeDirection,
            startDistance: Double
        ): EdgeTraversal? {
            val endDistance = when (direction) {
                EdgeDirection.FIRST_TO_SECOND -> edge.lengthMeters
                EdgeDirection.SECOND_TO_FIRST -> 0.0
            }
            return if (samePosition(startDistance, endDistance)) {
                null
            } else {
                EdgeTraversal(edge.id, direction, startDistance, endDistance)
            }
        }

        private fun allows(traversal: EdgeTraversal): Boolean {
            val lower = minOf(traversal.startDistanceMeters, traversal.endDistanceMeters)
            val upper = maxOf(traversal.startDistanceMeters, traversal.endDistanceMeters)
            var edgeOffset = 0.0
            for (slice in topology.getEdge(traversal.edgeId).railSlices) {
                val sliceEnd = edgeOffset + slice.lengthMeters
                if (minOf(upper, sliceEnd) > maxOf(lower, edgeOffset)) {
                    val railRunsFirstToSecond = slice.endAbMeters > slice.startAbMeters
                    val traversalRunsFirstToSecond =
                        traversal.direction == EdgeDirection.FIRST_TO_SECOND
                    val facing = if (railRunsFirstToSecond == traversalRunsFirstToSecond) {
                        ViewDirection.AB
                    } else {
                        ViewDirection.BA
                    }
                    if (!directions.allows(slice.railId, facing)) return false
                }
                edgeOffset = sliceEnd
            }
            return true
        }

        private fun offer(state: ArrivalState, distance: Double, predecessor: Predecessor) {
            val previous = distances[state]
            if (previous != null && distance >= previous) return
            distances[state] = distance
            predecessors[state] = predecessor
            pending += QueueEntry(state, distance, sequence++)
        }

        private fun considerGoal(
            previousState: ArrivalState?,
            finalTraversal: EdgeTraversal?,
            terminalMarker: RouteSegmentSnapshot?,
            distance: Double
        ) {
            if (bestGoal == null || distance < bestGoal!!.distance) {
                bestGoal = GoalCandidate(distance, previousState, finalTraversal, terminalMarker)
            }
        }

        private fun rebuildPath(goal: GoalCandidate): SearchPath {
            val reversed = ArrayList<EdgeTraversal>()
            goal.finalTraversal?.let(reversed::add)
            var state = goal.previousState
            while (state != null) {
                val predecessor = predecessors.getValue(state)
                predecessor.traversal?.let(reversed::add)
                state = predecessor.previousState
            }
            reversed.reverse()
            return SearchPath(reversed, goal.terminalMarker)
        }
    }

    private fun buildIncidentEnds(topology: TopologyState): Map<TopologyNodeId, List<IncidentEnd>> {
        val result = topology.nodes.keys.associateWithTo(linkedMapOf()) { mutableListOf<IncidentEnd>() }
        topology.edges.values.sortedBy { edge -> edge.id.index }.forEach { edge ->
            result.getValue(edge.first.nodeId) += IncidentEnd(
                edge,
                edge.first,
                EdgeDirection.FIRST_TO_SECOND,
                0.0
            )
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
        is TopologyNode.Turnout ->
            departurePort in node.legalContinuations.getValue(arrivalPort)
    }

    private fun EdgeTraversal.toSegments(edge: TrackEdge): List<RouteSegmentSnapshot> {
        val lower = minOf(startDistanceMeters, endDistanceMeters)
        val upper = maxOf(startDistanceMeters, endDistanceMeters)
        val slices = edge.railSlices.withEdgeOffsets()
        val ordered = when (direction) {
            EdgeDirection.FIRST_TO_SECOND -> slices
            EdgeDirection.SECOND_TO_FIRST -> slices.asReversed()
        }
        return ordered.mapNotNull { slice ->
            val overlapStart = maxOf(lower, slice.edgeStartMeters)
            val overlapEnd = minOf(upper, slice.edgeEndMeters)
            if (overlapEnd <= overlapStart) return@mapNotNull null
            val canonicalAtLower = slice.canonicalAbAt(overlapStart)
            val canonicalAtUpper = slice.canonicalAbAt(overlapEnd)
            val canonicalStart: Double
            val canonicalEnd: Double
            if (direction == EdgeDirection.FIRST_TO_SECOND) {
                canonicalStart = canonicalAtLower
                canonicalEnd = canonicalAtUpper
            } else {
                canonicalStart = canonicalAtUpper
                canonicalEnd = canonicalAtLower
            }
            val facing = if (canonicalEnd > canonicalStart) ViewDirection.AB else ViewDirection.BA
            val railLength = abs(slice.railEndAbMeters - slice.railStartAbMeters)
            val localStart = if (facing == ViewDirection.AB) canonicalStart else railLength - canonicalStart
            val localEnd = if (facing == ViewDirection.AB) canonicalEnd else railLength - canonicalEnd
            RouteSegmentSnapshot(
                railId = slice.railId,
                facing = facing,
                startArcLengthMeters = localStart,
                endArcLengthMeters = localEnd
            )
        }
    }

    private fun List<work.art1st.javarailways.core.api.physics.rail.topology.RailSliceRef>.withEdgeOffsets():
        List<SliceWithOffset> {
        var offset = 0.0
        return map { slice ->
            val result = SliceWithOffset(
                railId = slice.railId,
                railStartAbMeters = slice.startAbMeters,
                railEndAbMeters = slice.endAbMeters,
                edgeStartMeters = offset,
                edgeEndMeters = offset + slice.lengthMeters
            )
            offset = result.edgeEndMeters
            result
        }
    }

    private fun SliceWithOffset.canonicalAbAt(edgeDistance: Double): Double {
        val localDistance = edgeDistance - edgeStartMeters
        return if (railEndAbMeters > railStartAbMeters) {
            railStartAbMeters + localDistance
        } else {
            railStartAbMeters - localDistance
        }
    }

    private fun TrackEdge.arrival(direction: EdgeDirection): TopologyEdgeEnd = when (direction) {
        EdgeDirection.FIRST_TO_SECOND -> second
        EdgeDirection.SECOND_TO_FIRST -> first
    }

    private fun TrackEdgeSlice.edgeDistanceAt(canonicalAbMeters: Double): Double =
        if (railEndAbMeters > railStartAbMeters) {
            edgeStartMeters + canonicalAbMeters - railStartAbMeters
        } else {
            edgeStartMeters + railStartAbMeters - canonicalAbMeters
        }

    private fun TopologyState.getEdge(edgeId: TrackEdgeId): TrackEdge =
        edges[edgeId] ?: error("topology edge $edgeId is missing from its own state")

    private fun samePosition(first: Double, second: Double): Boolean = abs(first - second) <= POSITION_EPSILON

    private fun RouteSegmentSnapshot.startsAt(position: RouteSegmentSnapshot): Boolean =
        railId == position.railId &&
            facing == position.facing &&
            samePosition(startArcLengthMeters, position.startArcLengthMeters)

    private fun rejected(reason: TopologyRoutePlanningRejection, detail: String) =
        TopologyRoutePlanningResult.Rejected(reason, detail)

    private const val POSITION_EPSILON = 1e-9

    private enum class EdgeDirection {
        FIRST_TO_SECOND,
        SECOND_TO_FIRST
    }

    private data class ResolvedLocation(
        val railId: UUID,
        val canonicalAbMeters: Double,
        val railLengthMeters: Double,
        val railRunsFirstToSecond: Boolean,
        val edgeId: TrackEdgeId,
        val edgeDistanceMeters: Double
    ) {
        fun edgeDirection(facing: ViewDirection): EdgeDirection {
            val runsFirstToSecond = when (facing) {
                ViewDirection.AB -> railRunsFirstToSecond
                ViewDirection.BA -> !railRunsFirstToSecond
            }
            return if (runsFirstToSecond) {
                EdgeDirection.FIRST_TO_SECOND
            } else {
                EdgeDirection.SECOND_TO_FIRST
            }
        }

        fun endpointMarker(edgeDirection: EdgeDirection): RouteSegmentSnapshot {
            val traversalRunsFirstToSecond = edgeDirection == EdgeDirection.FIRST_TO_SECOND
            val facing = if (railRunsFirstToSecond == traversalRunsFirstToSecond) {
                ViewDirection.AB
            } else {
                ViewDirection.BA
            }
            val localMeters = if (facing == ViewDirection.AB) {
                canonicalAbMeters
            } else {
                railLengthMeters - canonicalAbMeters
            }
            return RouteSegmentSnapshot(railId, facing, localMeters, localMeters)
        }
    }

    private data class EdgeTraversal(
        val edgeId: TrackEdgeId,
        val direction: EdgeDirection,
        val startDistanceMeters: Double,
        val endDistanceMeters: Double
    ) {
        val lengthMeters: Double = abs(endDistanceMeters - startDistanceMeters)

        init {
            require(lengthMeters > 0.0)
        }
    }

    private data class ArrivalState(
        val nodeId: TopologyNodeId,
        val arrivalPort: RailEndpointRef
    )

    private data class IncidentEnd(
        val edge: TrackEdge,
        val end: TopologyEdgeEnd,
        val direction: EdgeDirection,
        val startDistance: Double
    )

    private data class QueueEntry(
        val state: ArrivalState,
        val distance: Double,
        val sequence: Long
    )

    private data class Predecessor(
        val previousState: ArrivalState?,
        val traversal: EdgeTraversal?
    )

    private data class GoalCandidate(
        val distance: Double,
        val previousState: ArrivalState?,
        val finalTraversal: EdgeTraversal?,
        val terminalMarker: RouteSegmentSnapshot?
    )

    private data class SearchPath(
        val traversals: List<EdgeTraversal>,
        val terminalMarker: RouteSegmentSnapshot?
    )

    private data class SliceWithOffset(
        val railId: UUID,
        val railStartAbMeters: Double,
        val railEndAbMeters: Double,
        val edgeStartMeters: Double,
        val edgeEndMeters: Double
    )
}
