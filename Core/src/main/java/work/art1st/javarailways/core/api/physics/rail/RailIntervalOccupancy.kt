package work.art1st.javarailways.core.api.physics.rail

import work.art1st.javarailways.core.api.physics.curve.Curve
import java.util.UUID

/** Direction-independent closed interval in canonical rail AB arc coordinates. */
data class RailInterval(
    val railId: UUID,
    val minArcLengthInAbMeters: Double,
    val maxArcLengthInAbMeters: Double
) {
    init {
        require(minArcLengthInAbMeters.isFinite() && maxArcLengthInAbMeters.isFinite())
        require(minArcLengthInAbMeters <= maxArcLengthInAbMeters)
    }

    fun overlaps(other: RailInterval, toleranceMeters: Double = Curve.TOLERANCE): Boolean {
        require(toleranceMeters.isFinite() && toleranceMeters >= 0.0)
        return railId == other.railId &&
            minArcLengthInAbMeters <= other.maxArcLengthInAbMeters + toleranceMeters &&
            other.minArcLengthInAbMeters <= maxArcLengthInAbMeters + toleranceMeters
    }
}

enum class IntervalOccupancyState {
    BEFORE_OCCUPANCY,
    OCCUPIED,
    RELEASED
}

/**
 * Tracks one claim against physical train intervals. Release requires an observed occupied state
 * followed by complete separation, so a reservation is never released merely because its train
 * has not entered yet. Direction and head/tail identity are deliberately irrelevant.
 */
class RailIntervalReleaseDetector(
    protectedIntervals: Collection<RailInterval>,
    private val toleranceMeters: Double = Curve.TOLERANCE
) {
    private val protectedIntervals = protectedIntervals.toList()
    var state: IntervalOccupancyState = IntervalOccupancyState.BEFORE_OCCUPANCY
        private set

    init {
        require(this.protectedIntervals.isNotEmpty())
        require(toleranceMeters.isFinite() && toleranceMeters >= 0.0)
    }

    fun update(trainIntervals: Collection<RailInterval>): IntervalOccupancyState {
        if (state == IntervalOccupancyState.RELEASED) return state
        val overlaps = trainIntervals.any { trainInterval ->
            protectedIntervals.any { protected -> protected.overlaps(trainInterval, toleranceMeters) }
        }
        state = when {
            overlaps -> IntervalOccupancyState.OCCUPIED
            state == IntervalOccupancyState.OCCUPIED -> IntervalOccupancyState.RELEASED
            else -> state
        }
        return state
    }
}
