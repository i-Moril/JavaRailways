package work.art1st.javarailways.simpleato.routing

import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.route.RailOperatingDirections
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState

/** A detached direction proof for one exact frozen path, including its protective suffix. */
internal class RouteOperatingDirectionLimit private constructor(
    val worldId: String,
    val route: RouteSnapshot,
    val endRouteDistanceMeters: Double
) {
    fun requireMatches(selection: FrozenRouteSelectionResult.Selected) {
        require(worldId == selection.observationStamp.worldId && route == selection.route) {
            "operating direction result belongs to another world or frozen route"
        }
    }

    fun requireLegalThrough(requiredEndRouteDistanceMeters: Double) {
        require(requiredEndRouteDistanceMeters.isFinite() && requiredEndRouteDistanceMeters >= 0.0)
        require(requiredEndRouteDistanceMeters <= endRouteDistanceMeters + 1.0e-6) {
            "operating route, train body or stopping margin crosses a forbidden operating direction " +
                "at $endRouteDistanceMeters route metres"
        }
    }

    companion object {
        fun calculate(worldId: String, route: RouteSnapshot,
            topology: TopologyState, directions: RailOperatingDirections): RouteOperatingDirectionLimit {
            require(worldId.isNotBlank())
            require(topology === directions.topology) {
                "operating directions belong to another topology"
            }
            var distance = 0.0
            var firstForbidden: Double? = null
            route.segments.forEach { segment ->
                val rail = topology.railProjection[segment.railId]?.singleOrNull()
                require(rail != null &&
                    directions.directions.containsKey(segment.railId)) {
                    "operating direction snapshot is missing rail ${segment.railId}"
                }
                require(segment.endArcLengthMeters <= kotlin.math.abs(rail.railEndAbMeters - rail.railStartAbMeters) + 1.0e-6) {
                    "operating route exceeds rail ${segment.railId}"
                }
                val allowed = directions.directions.getValue(segment.railId)
                if (firstForbidden == null && allowed != null && allowed != segment.facing &&
                    segment.lengthMeters > 0.0) firstForbidden = distance
                distance += segment.lengthMeters
            }
            return RouteOperatingDirectionLimit(worldId, route, firstForbidden ?: distance)
        }
    }
}
