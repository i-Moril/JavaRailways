package work.art1st.javarailways.simpleato.routing

import work.art1st.javarailways.basic.api.route.RailViewRef
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import kotlin.math.abs
import work.art1st.javarailways.simpleato.api.mission.AtoMission

/** Immutable projection helper from published train positions to Mission route progress. */
class MissionRouteLayout(
    world: World,
    val mission: AtoMission
) {
    private data class LocatedSegment(
        val railViewRef: RailViewRef,
        val railLengthMeters: Double,
        val startArcLengthMeters: Double,
        val endArcLengthMeters: Double,
        val cumulativeStartMeters: Double
    )

    private val segments: List<LocatedSegment>

    val totalLengthMeters: Double

    init {
        var legStart = 0.0
        val locatedSegments = ArrayList<LocatedSegment>()
        mission.legs.forEach { leg ->
            var segmentStart = legStart
            leg.plannedRoute.expandedRoute.segments.forEach { segment ->
                val rail = requireNotNull(world.getRail(segment.railViewRef.railId)) {
                    "planned route rail no longer exists: ${segment.railViewRef.railId}"
                }
                locatedSegments += LocatedSegment(
                    railViewRef = segment.railViewRef,
                    railLengthMeters = rail.getView(ViewDirection.AB).curve.totalArcLength,
                    startArcLengthMeters = segment.startArcLength,
                    endArcLengthMeters = segment.endArcLength,
                    cumulativeStartMeters = segmentStart
                )
                segmentStart += segment.length
            }
            legStart += leg.plannedRoute.expandedRoute.totalLength
        }
        segments = locatedSegments.toList()
        totalLengthMeters = legStart
        (mission.origin as? work.art1st.javarailways.simpleato.api.mission.AtoMissionOrigin.Physical)?.let { origin ->
            val first = segments.first()
            val arc = if (origin.position.facing == ViewDirection.AB) origin.position.arcLengthInAbMeters
                else first.railLengthMeters - origin.position.arcLengthInAbMeters
            require(first.railViewRef.railId == origin.position.railId &&
                first.railViewRef.direction == origin.position.facing && abs(first.startArcLengthMeters - arc) <= 1e-8) {
                "formation Mission route does not start at its exact published origin"
            }
        }
    }

    /**
     * Resolve [position] on the Mission distance axis. [previousProgressMeters] keeps repeated rails
     * and loop routes monotonic after the train has entered the Mission.
     * Segment membership uses the actual directed interval, not a stopping-position tolerance:
     * snapping a position back to a segment endpoint would hide that a signal has been passed.
     */
    fun project(
        position: RailPositionSnapshot,
        previousProgressMeters: Double? = null
    ): Double? = project(position, previousProgressMeters, null)

    /** Expected progress only selects an occurrence; it never changes membership or monotonicity. */
    internal fun project(
        position: RailPositionSnapshot,
        previousProgressMeters: Double?,
        expectedProgressMeters: Double?
    ): Double? {
        val candidates = segments.mapNotNull { segment ->
            if (segment.railViewRef.railId != position.railId ||
                segment.railViewRef.direction != position.facing
            ) return@mapNotNull null
            val arcInView = when (segment.railViewRef.direction) {
                ViewDirection.AB -> position.arcLengthInAbMeters
                ViewDirection.BA -> segment.railLengthMeters - position.arcLengthInAbMeters
            }
            if (arcInView < segment.startArcLengthMeters ||
                arcInView > segment.endArcLengthMeters
            ) return@mapNotNull null
            segment.cumulativeStartMeters + (arcInView - segment.startArcLengthMeters)
        }
        val forwardCandidates = if (previousProgressMeters == null) candidates
            else candidates.filter { it >= previousProgressMeters }
        return if (expectedProgressMeters == null) forwardCandidates.minOrNull()
            else forwardCandidates.minByOrNull { abs(it - expectedProgressMeters) }
    }
}
