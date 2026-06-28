package work.art1st.mineRails.core.physics.rail.curve

import org.joml.Vector3d
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.api.physics.curve.PrimeCurve
import work.art1st.mineRails.core.api.util.annotation.Heavy
import work.art1st.mineRails.core.api.util.WeakReferenceWrapper
import java.util.stream.Stream

/**
 * 反向曲线：弧长反转，切线取反。
 * arcLength=0 对应原曲线的 totalArcLength，反之亦然。
 */
class ReversedCurve(
    origCurve: PrimeCurve,
) : PrimeCurve {
    private val origCurve: PrimeCurve by WeakReferenceWrapper(origCurve)
    override val totalArcLength: Double
        get() = origCurve.totalArcLength
    override val reversed: PrimeCurve
        get() = origCurve

    override fun getPointAt(arcLength: Double): Curve.Point {
        val revArc = reverseArcLength(arcLength, origCurve)
        val p = origCurve.getPointAt(revArc)
        return Curve.Point(
            position = p.position,
            tangent = Vector3d(p.tangent).negate(),
            normal = p.normal,
            curve = this,
            arcLength = arcLength
        )
    }

    @Heavy
    override fun stream(): Stream<Curve.Point> = origCurve.stream()

    override fun save(): PrimeCurve.SavedPrimeCurve = origCurve.save()
}
