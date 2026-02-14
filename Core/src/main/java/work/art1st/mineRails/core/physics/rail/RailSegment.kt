package work.art1st.mineRails.core.physics.rail

import work.art1st.mineRails.core.util.BinarySearch
import kotlin.math.abs

data class RailSegment(
    val railView: RailView,
    val startIdx: Int,
    val endIdx: Int
) {
    constructor(railView: RailView, start: Double, end: Double) :
            this(railView, railView.curve.getIdxAt(start), railView.curve.getIdxAt(end))
    constructor(railView: RailView) :
            this(railView, 0, railView.curve.totalPoints - 1)
    constructor(railSegment: RailSegment, start: Double, end: Double) :
            this(railSegment.railView, railSegment.startArcLength + start, railSegment.startArcLength + end)

    val startArcLength: Double by lazy { railView.curve.getPointAt(startIdx).arcLength }
    val endArcLength: Double by lazy { railView.curve.getPointAt(endIdx).arcLength }
    val totalArcLength
        get() = endArcLength - startArcLength
}

class RailSegments internal constructor(
    private val segments: List<RailSegment>
) : List<RailSegment> by segments {
    fun searchRailLocation(tolerance: Double, condition: ((RailLocationWithFacing) -> Double)): RailLocationWithFacing? {
        return searchRailLocation(tolerance, {x -> x}, condition)
    }

    fun <T> searchRailLocation(tolerance: Double, f: ((RailLocationWithFacing) -> T), condition: ((T) -> Double)): T? {
        for (segment in segments) {
            val result = BinarySearch.searchIntegerBoundary(segment.startIdx, segment.endIdx,
                {f(segment.railView.getRailLocationAt(it))}, condition)
            if (abs(result.third) < tolerance) {
                return result.second
            }
        }
        return null
    }
}