package work.art1st.mineRails.core.physics.rail

import work.art1st.mineRails.core.api.physics.rail.RailLocation
import work.art1st.mineRails.core.api.physics.curve.Curve

@ConsistentCopyVisibility
data class RailLocationWithoutFacing private constructor(
    override val rail: CoreRail,
    val point: Curve.Point,     // Point is always viewAB, guaranteed by internal constructor
) : RailLocation {
    // 根据 viewAB 上的弧长构造
    internal constructor(rail: CoreRail, arcLengthInCurveAB: Double) : this(
        rail, rail.viewAB.curve.getPointAt(arcLengthInCurveAB)
    )

    override val position
        get() = point.position

    override val tangent
        get() = point.tangent

    override val normal
        get() = point.normal

    fun withFacing(facing: RailView.ViewDirection): RailLocationWithFacing {
        val arcLength = when (facing) {
            RailView.ViewDirection.AB -> point.arcLength
            RailView.ViewDirection.BA -> point.curve.totalArcLength - point.arcLength
        }
        return RailLocationWithFacing(rail.getView(facing), arcLength)
    }

    override fun arcLengthIn(facing: RailView.ViewDirection): Double {
        return when (facing) {
            RailView.ViewDirection.AB -> point.arcLength
            RailView.ViewDirection.BA -> point.curve.totalArcLength - point.arcLength
        }
    }
}
