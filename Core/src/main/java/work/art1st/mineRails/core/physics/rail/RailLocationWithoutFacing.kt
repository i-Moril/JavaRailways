package work.art1st.mineRails.core.physics.rail

import work.art1st.mineRails.core.api.physics.RailLocation
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.physics.rail.curve.reverseIdx

@ConsistentCopyVisibility
data class RailLocationWithoutFacing private constructor(
    override val rail: Rail,
    val point: Curve.Point,     // Point is always viewAB, guaranteed by internal constructor
) : RailLocation {
    internal constructor(rail: Rail, idxInCurveAB: Int) : this(rail, rail.viewAB.curve.getPointAt(idxInCurveAB)!!)

    override val position
        get() = point.position

    override val tangent
        get() = point.tangent

    override val normal
        get() = point.normal

    fun withFacing(facing: RailView.ViewDirection): RailLocationWithFacing {
        return RailLocationWithFacing(rail.getView(facing), when(facing) {
            RailView.ViewDirection.AB -> point.idx
            RailView.ViewDirection.BA -> reverseIdx(point.idx, point.curve)
        })
    }

    /**
     * 在PrimeCurve上的弧长
     */
    override fun arcLengthIn(facing: RailView.ViewDirection): Double {
        return when (facing) {
            RailView.ViewDirection.AB -> point.arcLength
            RailView.ViewDirection.BA -> point.curve.totalArcLength - point.arcLength
        }
    }
}
