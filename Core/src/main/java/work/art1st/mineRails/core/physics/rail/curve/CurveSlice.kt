package work.art1st.mineRails.core.physics.rail.curve

import work.art1st.mineRails.core.util.Heavy
import java.util.stream.Stream

internal class CurveSlice(
    private val origCurve: Curve,
    private val startIdx: Int,
    private val endIdx: Int
) : Curve {

    private val startArcLength: Double = origCurve.getPointAt(startIdx)!!.arcLength
    override val totalArcLength: Double = origCurve.getPointAt(endIdx - 1)!!.arcLength - startArcLength
    override val totalPoints: Int = endIdx - startIdx
    override val reversed: Curve = ReversedCurve(this)

    override fun slice(startIdx: Int, endIdx: Int): Curve {
        val startIdx = if (startIdx <= 0) {
            0
        } else if (startIdx >= this.totalPoints) {
            this.totalPoints
        } else {
            startIdx
        }
        val endIdx = if (endIdx <= 0) {
            0
        } else if (endIdx >= this.totalPoints) {
            this.totalPoints
        } else {
            endIdx
        }
        if (startIdx > endIdx) return CurveSlice(origCurve, this.startIdx + endIdx, this.startIdx + startIdx).reversed
        return CurveSlice(origCurve, this.startIdx + startIdx, this.startIdx + endIdx)
    }

    override fun getPointAt(arcLength: Double): Curve.Point? {
        return origCurve.getPointAt(arcLength - startArcLength)
    }

    override fun getPointAt(idx: Int): Curve.Point? {
        return origCurve.getPointAt(idx - startArcLength)
    }

    @Heavy
    override fun stream(): Stream<Curve.Point> {
        return origCurve.stream().skip(startIdx.toLong()).limit(totalPoints.toLong())
    }
}