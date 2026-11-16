package work.art1st.javarailways.simpleato.api.line

import work.art1st.javarailways.simpleato.api.destination.DestinationRole
import work.art1st.javarailways.simpleato.api.station.StationId
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.basic.api.route.ExpandedRoute
import work.art1st.javarailways.simpleato.routing.RouteBuildResult
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simpleato.routing.OperationalRoutePlanner
import work.art1st.javarailways.simpleato.simpleAtoWorldData

sealed interface LineCompileResult {
    data class Success(val line: Line, val legs: List<CompiledLineLeg>) : LineCompileResult {
        val totalLengthMeters: Double get() = legs.sumOf { it.route.totalLength }
    }
    data class Failure(val line: Line, val legIndex: Int, val reason: String) : LineCompileResult
}

data class CompiledLineLeg(
    val fromStationId: String,
    val toStationId: String,
    val fromDestinationId: String,
    val toDestinationId: String,
    val route: ExpandedRoute
)

data class CompiledLineResetCandidate(
    val originDepartureDirection: ViewDirection,
    val compilation: LineCompileResult.Success
)

/** Resolves station berth combinations and chooses the shortest directed route for each line leg. */
class LinePlanner(private val world: World) {
    private val routePlanner = OperationalRoutePlanner(world)

    fun compile(line: Line, direction: LineDirection = LineDirection.UP): LineCompileResult =
        compileStations(line, line.stations(direction))

    /**
     * Searches a whole exact-berth route while carrying the actual direction through every stop.
     * Both directions may be supplied for an origin whose departure orientation is unknown during Reset.
     */
    fun compileResetCandidates(
        line: Line,
        stationIds: List<StationId>,
        destinationIds: List<String>,
        originDirections: Collection<ViewDirection> = ViewDirection.entries,
        viaIds: List<List<String>> = List(stationIds.size) { emptyList() },
        requireUniquePath: Boolean = false,
        pruneNonTargetWaypoints: Boolean = false
    ): List<CompiledLineResetCandidate> {
        require(viaIds.size == stationIds.size && viaIds.firstOrNull().orEmpty().isEmpty()) { "viaIds must match station count and the origin must have no incoming via points" }
        require(viaIds.flatten().all { it.isNotBlank() }) { "via waypoint IDs must not be blank" }
        viaIds.flatten().forEach { id -> require(world.basicWorldData.wayPoints[id] != null) { "Unknown via waypoint: $id" } }
        require(stationIds.size >= 2) { "A line section requires at least two stations" }
        require(destinationIds.size == stationIds.size) { "Selected berth count must match station count" }
        val destinationService = world.simpleAtoWorldData.destinations
        val destinations = destinationIds.mapIndexed { index, destinationId ->
            val destination = requireNotNull(destinationService[destinationId]) { "Unknown berth destination: $destinationId" }
            val role = destination.role as? DestinationRole.StationBerthRole
                ?: throw IllegalArgumentException("Destination is not a station berth: $destinationId")
            require(role.stationId == stationIds[index]) {
                "Selected berth $destinationId does not belong to station ${stationIds[index]}"
            }
            destination
        }
        data class Candidate(
            val originDirection: ViewDirection,
            val currentDirection: ViewDirection,
            val legs: List<CompiledLineLeg>,
            val totalLength: Double
        )
        var candidates = originDirections.distinct().map { Candidate(it, it, emptyList(), 0.0) }
        stationIds.zipWithNext().forEachIndexed { index, (fromStation, toStation) ->
            val start = destinations[index]
            val goal = destinations[index + 1]
            val goalDirections: List<ViewDirection?> = if (requireUniquePath) listOf(goal.approachDirection)
                else goal.approachDirection?.let(::listOf) ?: ViewDirection.entries
            var lastFailure: RouteBuildResult.Failure? = null
            candidates = candidates.flatMap { candidate ->
                goalDirections.mapNotNull { goalDirection ->
                    val planned = buildViaRoute(
                        from = start.toWayPoint(), to = goal.toWayPoint(), viaIds = viaIds[index + 1],
                        requiredGoalDirection = goalDirection, requiredStartDirection = candidate.currentDirection,
                        requireUniquePath = requireUniquePath, pruneNonTargetWaypoints = pruneNonTargetWaypoints
                    )
                    if (planned is RouteBuildResult.Failure) {
                        lastFailure = planned
                        if (requireUniquePath && planned.planningRejection != work.art1st.javarailways.basic.api.route.planning.TopologyRoutePlanningRejection.UNREACHABLE) {
                            throw IllegalArgumentException("Line leg $index: ${planned.reason}")
                        }
                    }
                    val result = planned as? RouteBuildResult.Success ?: return@mapNotNull null
                    val actualEndDirection = result.route.segments.last().railViewRef.direction
                    val leg = CompiledLineLeg(
                        fromStation.value,
                        toStation.value,
                        start.id,
                        goal.id,
                        result.route
                    )
                    Candidate(
                        candidate.originDirection,
                        actualEndDirection,
                        candidate.legs + leg,
                        candidate.totalLength + result.route.totalLength
                    )
                }
            }.let { alternatives ->
                if (requireUniquePath) alternatives else alternatives.groupBy { it.originDirection to it.currentDirection }
                    .values.map { choices -> choices.minBy(Candidate::totalLength) }
            }
            if (candidates.isEmpty()) {
                if (requireUniquePath) lastFailure?.let { throw IllegalArgumentException("Line leg $index: ${it.reason}") }
                return emptyList()
            }
        }
        return candidates.groupBy(Candidate::originDirection).map { (originDirection, alternatives) ->
            if (requireUniquePath && alternatives.size > 1) throw IllegalArgumentException("Multiple legal physical routes complete the selected line section")
            val best = alternatives.minBy(Candidate::totalLength)
            CompiledLineResetCandidate(originDirection, LineCompileResult.Success(line, best.legs))
        }
    }

    private fun buildViaRoute(
        from: work.art1st.javarailways.basic.WayPoint,
        to: work.art1st.javarailways.basic.WayPoint,
        viaIds: List<String>,
        requiredGoalDirection: ViewDirection?,
        requiredStartDirection: ViewDirection?,
        requireUniquePath: Boolean,
        pruneNonTargetWaypoints: Boolean
    ): RouteBuildResult {
        val via = viaIds.map { id -> world.basicWorldData.wayPoints[id]
            ?: return RouteBuildResult.Failure("Unknown via waypoint: $id") }
        var previous = from
        var facing = requiredStartDirection
        val segments = ArrayList<work.art1st.javarailways.basic.api.route.RouteSegment>()
        for ((index, target) in (via + to).withIndex()) {
            val result = routePlanner.buildRoute(previous, target,
                requiredGoalDirection = if (index == via.size) requiredGoalDirection
                    else world.simpleAtoWorldData.destinations[target.id]?.approachDirection,
                requiredStartDirection = facing,
                requireUniquePath = requireUniquePath, pruneNonTargetWaypoints = pruneNonTargetWaypoints)
            if (result is RouteBuildResult.Failure) return result
            result as RouteBuildResult.Success
            segments += result.route.segments
            facing = result.route.segments.last().railViewRef.direction
            previous = target
        }
        return RouteBuildResult.Success(ExpandedRoute(from.id, to.id, segments))
    }

    /** Compiles an ordered section, optionally pinning every station to an exact berth destination. */
    fun compileStations(
        line: Line,
        stationIds: List<StationId>,
        destinationIds: List<String>? = null
    ): LineCompileResult {
        if (stationIds.size < 2) return LineCompileResult.Failure(line, 0, "A line section requires at least two stations")
        if (destinationIds != null && destinationIds.size != stationIds.size) {
            return LineCompileResult.Failure(line, 0, "Selected berth count must match station count")
        }
        val destinationService = world.simpleAtoWorldData.destinations
        val destinationsByStation = destinationService.all().mapNotNull { destination ->
            val role = destination.role as? DestinationRole.StationBerthRole ?: return@mapNotNull null
            role.stationId to destination
        }.groupBy({ it.first }, { it.second })
        val selectedDestinations = destinationIds?.mapIndexed { index, destinationId ->
            val destination = destinationService[destinationId]
                ?: return LineCompileResult.Failure(line, index.coerceAtMost(stationIds.lastIndex - 1), "Unknown berth destination: $destinationId")
            val role = destination.role as? DestinationRole.StationBerthRole
                ?: return LineCompileResult.Failure(line, index.coerceAtMost(stationIds.lastIndex - 1), "Destination is not a station berth: $destinationId")
            if (role.stationId != stationIds[index]) {
                return LineCompileResult.Failure(
                    line,
                    index.coerceAtMost(stationIds.lastIndex - 1),
                    "Selected berth $destinationId does not belong to station ${stationIds[index]}"
                )
            }
            destination
        }

        val legs = ArrayList<CompiledLineLeg>()
        stationIds.zipWithNext().forEachIndexed { index, (fromStation, toStation) ->
            val starts = selectedDestinations?.let { listOf(it[index]) } ?: destinationsByStation[fromStation].orEmpty()
            val goals = selectedDestinations?.let { listOf(it[index + 1]) } ?: destinationsByStation[toStation].orEmpty()
            if (starts.isEmpty()) return LineCompileResult.Failure(line, index, "Station has no berth: $fromStation")
            if (goals.isEmpty()) return LineCompileResult.Failure(line, index, "Station has no berth: $toStation")

            val candidates = starts.flatMap { start ->
                goals.mapNotNull { goal ->
                    when (val result = routePlanner.buildRoute(
                        from = start.toWayPoint(),
                        to = goal.toWayPoint(),
                        requiredGoalDirection = goal.approachDirection,
                        requiredStartDirection = start.approachDirection
                    )) {
                        is RouteBuildResult.Success -> Triple(start.id, goal.id, result.route)
                        is RouteBuildResult.Failure -> null
                    }
                }
            }
            val best = candidates.minByOrNull { it.third.totalLength }
                ?: return LineCompileResult.Failure(
                    line,
                    index,
                    "No directed route between $fromStation and $toStation"
                )
            legs += CompiledLineLeg(
                fromStationId = fromStation.value,
                toStationId = toStation.value,
                fromDestinationId = best.first,
                toDestinationId = best.second,
                route = best.third
            )
        }
        return LineCompileResult.Success(line, legs)
    }
}
