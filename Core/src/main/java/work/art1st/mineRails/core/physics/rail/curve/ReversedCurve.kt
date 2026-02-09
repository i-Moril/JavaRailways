package work.art1st.mineRails.core.physics.rail.curve

import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.util.Heavy
import java.lang.ref.WeakReference
import java.util.stream.Stream

class ReversedCurve(
    origCurve: Curve,
) : Curve {
    private val _origCurve: WeakReference<Curve> = WeakReference(origCurve)
    private val origCurve
        get() = _origCurve.get()!!
    override val totalArcLength: Double
        get() = origCurve.totalArcLength
    override val totalPoints: Int
        get() = origCurve.totalPoints
    override val reversed: Curve
        get() = origCurve

    override fun getPointAt(arcLength: Double): Curve.Point? {
        return origCurve.getPointAt(totalArcLength - arcLength)
    }

    override fun getPointAt(idx: Int): Curve.Point? {
        return origCurve.getPointAt(totalArcLength - idx)
    }

    @Heavy
    override fun stream(): Stream<Curve.Point> {
        return origCurve.stream()
    }
}