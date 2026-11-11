package work.art1st.javarailways.basic.infrastructure.admission

import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import java.util.UUID
import kotlin.math.max

/**
 * Canonical physical intervals checked before one compiled route may be admitted.
 *
 * This is validation metadata, not a claim footprint: it contains physical query geometry for
 * shared resources such as both arms of a diamond, even though those arms are represented by one
 * stable claim resource. Intervals are immutable, sorted by rail identity, and merged per rail.
 */
internal class OccupancyQueryGeometry private constructor(
    val intervals: List<RailInterval>
) {
    companion object {
        fun create(intervals: Collection<RailInterval>): OccupancyQueryGeometry {
            val merged = ArrayList<RailInterval>()
            intervals.groupBy(RailInterval::railId)
                .toSortedMap(compareBy(UUID::toString))
                .forEach { (_, railIntervals) ->
                    railIntervals.sortedWith(
                        compareBy(
                            RailInterval::minArcLengthInAbMeters,
                            RailInterval::maxArcLengthInAbMeters
                        )
                    ).forEach { interval ->
                        val previous = merged.lastOrNull()?.takeIf { it.railId == interval.railId }
                        if (previous != null &&
                            interval.minArcLengthInAbMeters <=
                            previous.maxArcLengthInAbMeters + Curve.TOLERANCE
                        ) {
                            merged[merged.lastIndex] = previous.copy(
                                maxArcLengthInAbMeters = max(
                                    previous.maxArcLengthInAbMeters,
                                    interval.maxArcLengthInAbMeters
                                )
                            )
                        } else {
                            merged += interval
                        }
                    }
                }
            return OccupancyQueryGeometry(java.util.List.copyOf(merged))
        }
    }
}
