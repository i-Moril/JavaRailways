package work.art1st.mineRails.basic.api.line

import work.art1st.mineRails.basic.api.destination.DestinationRole
import work.art1st.mineRails.basic.pathFinding.basicWorldData
import work.art1st.mineRails.basic.route.ExpandedRoute
import work.art1st.mineRails.basic.route.RouteBuildResult
import work.art1st.mineRails.basic.route.RoutePlanner
import work.art1st.mineRails.core.api.physics.World

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

/** Resolves station berth combinations and chooses the shortest directed route for each line leg. */
class LinePlanner(private val world: World) {
    private val routePlanner = RoutePlanner(world)

    fun compile(line: Line, direction: LineDirection = LineDirection.UP): LineCompileResult {
        val destinationsByStation = world.basicWorldData.destinations.all().mapNotNull { destination ->
            val role = destination.role as? DestinationRole.StationBerthRole ?: return@mapNotNull null
            role.stationId to destination
        }.groupBy({ it.first }, { it.second })

        val legs = ArrayList<CompiledLineLeg>()
        line.stations(direction).zipWithNext().forEachIndexed { index, (fromStation, toStation) ->
            val starts = destinationsByStation[fromStation].orEmpty()
            val goals = destinationsByStation[toStation].orEmpty()
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
