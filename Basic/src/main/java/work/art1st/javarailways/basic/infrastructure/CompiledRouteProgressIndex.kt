package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import java.util.Collections
import java.util.UUID

/** Result of locating one physical position on an already validated frozen route. */
internal sealed interface RouteProgressProjection {
    data class Resolved(val routeDistanceMeters: Double) : RouteProgressProjection {
        init {
            require(routeDistanceMeters.isFinite() && routeDistanceMeters >= 0.0)
        }
    }

    /** The exact rail and facing have no occurrence containing the physical position. */
    data object OffRoute : RouteProgressProjection

    /** The physical position occurs at multiple distinct distances along the frozen route. */
    class Ambiguous private constructor(
        val routeDistancesMeters: List<Double>
    ) : RouteProgressProjection {
        init {
            require(routeDistancesMeters.size >= 2)
            require(routeDistancesMeters.all { it.isFinite() && it >= 0.0 })
        }

        companion object {
            fun create(routeDistancesMeters: Collection<Double>): Ambiguous =
                Ambiguous(java.util.List.copyOf(routeDistancesMeters))
        }
    }
}

/**
 * Immutable physical-position index compiled once from a validated frozen route.
 *
 * Occurrences are bucketed by exact rail identity and route facing. A lookup therefore scans only
 * occurrences that can contain the supplied position; it never rescans unrelated route segments.
 */
internal class CompiledRouteProgressIndex private constructor(
    private val occurrencesByRailAndFacing: Map<RailFacingKey, List<IndexedOccurrence>>
) {
    fun project(position: RailPositionSnapshot): RouteProgressProjection {
        val occurrences = occurrencesByRailAndFacing[RailFacingKey(position.railId, position.facing)]
            ?: return RouteProgressProjection.OffRoute
        val candidates = occurrences.mapNotNull { occurrence ->
            occurrence.project(position.arcLengthInAbMeters)
        }
        if (candidates.isEmpty()) return RouteProgressProjection.OffRoute

        val minimum = candidates.minOrNull()!!
        val maximum = candidates.maxOrNull()!!
        return if (maximum - minimum <= Curve.TOLERANCE) {
            // The lower representative cannot move release progress ahead due only to tolerance.
            RouteProgressProjection.Resolved(minimum)
        } else {
            RouteProgressProjection.Ambiguous.create(candidates)
        }
    }

    companion object {
        fun compile(route: ProjectedTopologyRoute): CompiledRouteProgressIndex {
            val mutableBuckets = LinkedHashMap<RailFacingKey, MutableList<IndexedOccurrence>>()
            route.occurrences.forEach { occurrence ->
                val key = RailFacingKey(occurrence.segment.railId, occurrence.segment.facing)
                mutableBuckets.getOrPut(key, ::ArrayList) += IndexedOccurrence(
                    facing = occurrence.segment.facing,
                    canonicalStartAbMeters = occurrence.canonicalStartAbMeters,
                    canonicalEndAbMeters = occurrence.canonicalEndAbMeters,
                    routeStartMeters = occurrence.routeStartMeters,
                    lengthMeters = occurrence.segment.lengthMeters
                )
            }
            val immutableBuckets = LinkedHashMap<RailFacingKey, List<IndexedOccurrence>>()
            mutableBuckets.forEach { (key, occurrences) ->
                immutableBuckets[key] = Collections.unmodifiableList(ArrayList(occurrences))
            }
            return CompiledRouteProgressIndex(Collections.unmodifiableMap(immutableBuckets))
        }
    }

    private data class RailFacingKey(
        val railId: UUID,
        val facing: ViewDirection
    )

    private data class IndexedOccurrence(
        val facing: ViewDirection,
        val canonicalStartAbMeters: Double,
        val canonicalEndAbMeters: Double,
        val routeStartMeters: Double,
        val lengthMeters: Double
    ) {
        fun project(canonicalAbMeters: Double): Double? {
            val minimum = minOf(canonicalStartAbMeters, canonicalEndAbMeters)
            val maximum = maxOf(canonicalStartAbMeters, canonicalEndAbMeters)
            if (canonicalAbMeters < minimum - Curve.TOLERANCE ||
                canonicalAbMeters > maximum + Curve.TOLERANCE
            ) {
                return null
            }
            val localOffset = when (facing) {
                ViewDirection.AB -> canonicalAbMeters - canonicalStartAbMeters
                ViewDirection.BA -> canonicalStartAbMeters - canonicalAbMeters
            }.coerceIn(0.0, lengthMeters)
            return routeStartMeters + localOffset
        }
    }
}
