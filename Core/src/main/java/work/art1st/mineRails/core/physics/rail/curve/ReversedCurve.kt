package work.art1st.mineRails.core.physics.rail.curve

import org.joml.Vector3d
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.api.physics.curve.PrimeCurve
import work.art1st.mineRails.core.api.util.annotation.Heavy
import java.util.stream.Stream

/**
 * 反向曲线：弧长反转，切线取反。
 * arcLength=0 对应原曲线的 totalArcLength，反之亦然。
 */
class ReversedCurve(
    origCurve: PrimeCurve,
) : PrimeCurve {
    internal val originalCurve: PrimeCurve = origCurve
    override val totalArcLength: Double
        get() = originalCurve.totalArcLength
    override val reversed: PrimeCurve
        get() = originalCurve

    override fun getPointAt(arcLength: Double): Curve.Point {
        val revArc = reverseArcLength(arcLength, originalCurve)
        val p = originalCurve.getPointAt(revArc)
        return Curve.Point(
            position = p.position,
            tangent = Vector3d(p.tangent).negate(),
            normal = p.normal,
            curve = this,
            arcLength = arcLength
        )
    }

    @Heavy
    override fun stream(): Stream<Curve.Point> = originalCurve.stream()
        .map { point ->
            Curve.Point(
                position = point.position,
                tangent = Vector3d(point.tangent).negate(),
                normal = point.normal,
                curve = this,
                arcLength = totalArcLength - point.arcLength
            )
        }
        .sorted(compareBy(Curve.Point::arcLength))

    override fun splitAt(arcLength: Double): Pair<PrimeCurve, PrimeCurve> {
        val (originalBefore, originalAfter) = originalCurve.splitAt(totalArcLength - arcLength)
        return originalAfter.reversed to originalBefore.reversed
    }

    override fun concatenate(next: PrimeCurve): PrimeCurve {
        val reversedNext = next as? ReversedCurve
            ?: throw IllegalArgumentException("A reversed curve can only concatenate another reversed curve")
        return reversedNext.originalCurve.concatenate(originalCurve).reversed
    }

    override fun save(): PrimeCurve.SavedPrimeCurve = originalCurve.save()
}
