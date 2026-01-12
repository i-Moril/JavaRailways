package work.art1st.mineRails.core.physics.rail

open class RailLocation(
    val rail: Rail,
    internal val idxInCurveAB: Int,
) {
    internal val point
        get() = rail.viewAB.curve.getPointAt(idxInCurveAB)!!

    val position
        get() = point.position

    val normal
        get() = point.normal

    val tangent
        get() = point.tangent

    fun withFacing(facing: RailView.ViewDirection): RailLocation {
        return RailLocationWithFacing(rail, idxInCurveAB, facing)
    }

    /**
     * 在PrimeCurve上的弧长
     */
    fun arcLengthIn(facing: RailView.ViewDirection): Double {
        return when (facing) {
            RailView.ViewDirection.AB -> point.arcLength
            RailView.ViewDirection.BA -> point.curve.totalArcLength - point.arcLength
        }
    }

    override fun equals(other: Any?): Boolean {
        if (other is RailLocation) {
            return rail == other.rail && idxInCurveAB == other.idxInCurveAB
        }
        return false
    }

    override fun hashCode(): Int {
        var result = rail.hashCode()
        result = 31 * result + idxInCurveAB.hashCode()
        return result
    }
}
