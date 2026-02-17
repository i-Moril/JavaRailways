package work.art1st.mineRails.core.physics.rail.curve

import org.joml.unaryMinus
import work.art1st.mineRails.core.api.physics.curve.Curve

fun reverseIdx(idx: Int, curve: Curve) = curve.totalPoints - idx - 1
fun reverseArcLength(arcLength: Double, curve: Curve) = curve.totalArcLength - arcLength

val Curve.Point.idxInReversedCurve: Int
    get() = reverseIdx(idx, curve)
val Curve.Point.arcLengthInReversedCurve: Double
    get() = reverseArcLength(arcLength, curve)
val Curve.Point.inReversedCurve: Curve.Point
    get() = Curve.Point(
        position = position,
        tangent = -tangent,   // 在反向的曲线上，切线也相反
        normal = normal,

        curve = curve.reversed,
        idx = idxInReversedCurve,
        arcLength = arcLengthInReversedCurve
    )