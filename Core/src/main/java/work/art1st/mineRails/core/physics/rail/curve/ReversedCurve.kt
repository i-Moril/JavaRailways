package work.art1st.mineRails.core.physics.rail.curve

import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.api.physics.curve.PrimeCurve
import work.art1st.mineRails.core.util.Heavy
import work.art1st.mineRails.core.util.WeakReferenceWrapper
import java.util.stream.Stream

class ReversedCurve(
    origCurve: PrimeCurve,
) : PrimeCurve {
    private val origCurve: PrimeCurve by WeakReferenceWrapper(origCurve)
    override val totalArcLength: Double
        get() = origCurve.totalArcLength
    override val totalPoints: Int
        get() = origCurve.totalPoints
    override val reversed: PrimeCurve
        get() = origCurve

    override fun getPointAt(idx: Int): Curve.Point
        = origCurve.getPointAt(reverseIdx(idx, origCurve)).inReversedCurve

    override fun getIdxAt(arcLength: Double): Int
        = reverseIdx(origCurve.getIdxAt(reverseArcLength(arcLength, origCurve)), origCurve)

    @Heavy
    override fun stream(): Stream<Curve.Point> {
        return origCurve.stream()
    }

    override fun save(): PrimeCurve.SavedPrimeCurve {
        return origCurve.save()
    }
}