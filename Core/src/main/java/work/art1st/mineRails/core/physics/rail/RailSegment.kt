package work.art1st.mineRails.core.physics.rail

import kotlin.math.abs

/**
 * 弧长区间，在一条 RailView 上从 [startArcLength] 到 [endArcLength]。
 * 不再依赖整数索引，完全基于弧长参数化。
 */
data class RailSegment(
    val railView: RailView,
    /** 起始弧长（在 railView.curve 坐标系中） */
    val startArcLength: Double,
    /** 终止弧长 */
    val endArcLength: Double
) {
    /** 取弧长范围内的子区间 */
    constructor(railSegment: RailSegment, start: Double, end: Double) :
            this(railSegment.railView, railSegment.startArcLength + start, railSegment.startArcLength + end)

    /** 整个 RailView 的区间 */
    constructor(railView: RailView) :
            this(railView, 0.0, railView.curve.totalArcLength)

    val totalArcLength: Double
        get() = endArcLength - startArcLength
}

class RailSegments internal constructor(
    private val segments: List<RailSegment>
) : List<RailSegment> by segments {

    /** 在弧长区间内二分搜索满足条件的位置 */
    fun searchRailLocation(tolerance: Double, condition: ((RailLocationWithFacing) -> Double)): RailLocationWithFacing? {
        return searchRailLocation(tolerance, { x -> x }, condition)
    }

    fun <T> searchRailLocation(tolerance: Double, f: ((RailLocationWithFacing) -> T), condition: ((T) -> Double)): T? {
        for (segment in segments) {
            val result = searchArcRange(segment, f, condition)
            if (result != null && abs(condition(result)) < tolerance) {
                return result
            }
        }
        return null
    }

    /** 在弧长范围内二分搜索，找到 condition 穿过零的位置 */
    private fun <T> searchArcRange(segment: RailSegment, f: (RailLocationWithFacing) -> T, condition: (T) -> Double): T? {
        var lo = segment.startArcLength
        var hi = segment.endArcLength
        val loResult = f(segment.railView.getRailLocationAt(lo))
        val hiResult = f(segment.railView.getRailLocationAt(hi))
        var vLo = condition(loResult)
        val vHi = condition(hiResult)
        // 端点之一满足，直接返回
        if (abs(vLo) < 1e-12) return loResult
        if (abs(vHi) < 1e-12) return hiResult
        // 无穿零点
        if (vLo * vHi > 0) return null

        var bestResult = if (abs(vLo) <= abs(vHi)) loResult else hiResult
        var bestValue = minOf(abs(vLo), abs(vHi))
        for (i in 0 until 50) { // 50 次二分足够 double 精度
            val mid = (lo + hi) / 2.0
            val t = f(segment.railView.getRailLocationAt(mid))
            val vMid = condition(t)
            if (abs(vMid) < 1e-12) return t
            if (abs(vMid) < bestValue) {
                bestResult = t
                bestValue = abs(vMid)
            }
            if (vLo * vMid < 0) {
                hi = mid
            } else {
                lo = mid
                vLo = vMid
            }
        }
        return bestResult
    }
}
