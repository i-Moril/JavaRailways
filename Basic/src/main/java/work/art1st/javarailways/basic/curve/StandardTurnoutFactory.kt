package work.art1st.javarailways.basic.curve

import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.util.annotation.Heavy
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ceil

enum class StandardTurnout(
    val number: Int,
    val radiusMeters: Double,
    val divergingSpeedKmH: Double
) {
    N4(4, 30.0, 10.0),
    N5(5, 50.0, 15.0),
    N6(6, 80.0, 20.0),
    N7(7, 100.0, 20.0),
    N8(8, 145.0, 25.0),
    N9(9, 180.0, 30.0),
    N10(10, 220.0, 35.0),
    N12(12, 330.0, 45.0),
    N14(14, 500.0, 55.0),
    N16(16, 700.0, 65.0),
    N18(18, 1100.0, 80.0),
    N20(20, 1400.0, 90.0),
    N24(24, 1600.0, 100.0),
    N30(30, 2700.0, 140.0),
    N38(38, 4000.0, 150.0),
    N42(42, 5000.0, 160.0),
    N60(60, 10000.0, 200.0);

    val branchAngleRadians: Double get() = atan(1.0 / number)
    val displayName: String get() = "${number}号 (R${radiusMeters.toInt()}m / ${divergingSpeedKmH.toInt()}km/h)"
}

data class StandardTurnoutBuildResult(
    val curve: PrimeCurve,
    val startTangent: Vector3d,
    val endTangent: Vector3d
)

object StandardTurnoutFactory {
    private data class DirectionCandidate(
        val startTangent: Vector3d,
        val endTangent: Vector3d,
        val turnSign: Double
    )

    /**
     * Builds the diverging route of a standard turnout. The two main-line directions and two
     * branch sides produce four valid end directions; the one closest to [towards] is selected.
     */
    @Heavy
    fun build(anchor: Anchor, towards: Vector3dc, standard: StandardTurnout): StandardTurnoutBuildResult? {
        val axis = Vector3d(anchor.tangent.x(), 0.0, anchor.tangent.z())
        val target = Vector3d(towards.x(), 0.0, towards.z())
        if (axis.lengthSquared() < 1.0e-12 || target.lengthSquared() < 1.0e-12) return null
        axis.normalize()
        target.normalize()

        val angle = standard.branchAngleRadians
        val candidates = listOf(1.0, -1.0).flatMap { directionSign ->
            val start = Vector3d(axis).mul(directionSign)
            listOf(1.0, -1.0).map { turnSign ->
                DirectionCandidate(
                    startTangent = start,
                    endTangent = Vector3d(start).rotateY(turnSign * angle).normalize(),
                    turnSign = turnSign
                )
            }
        }
        val selected = candidates.maxBy { it.endTangent.dot(target) }
        val curve = circularBranch(anchor.position, selected, standard.radiusMeters, angle)
        return StandardTurnoutBuildResult(curve, Vector3d(selected.startTangent), Vector3d(selected.endTangent))
    }

    private fun circularBranch(
        startPosition: Vector3dc,
        direction: DirectionCandidate,
        radius: Double,
        angle: Double
    ): PrimeCurve {
        val totalLength = radius * angle
        val sampleCount = maxOf(2, ceil(totalLength / 0.2).toInt())
        val centerDirection = Vector3d(direction.startTangent).rotateY(direction.turnSign * PI / 2.0)
        val center = Vector3d(startPosition).fma(radius, centerDirection)
        val startRadius = Vector3d(startPosition).sub(center)
        val points = (0..sampleCount).map { index ->
            val ratio = index.toDouble() / sampleCount
            val arc = totalLength * ratio
            val rotation = direction.turnSign * angle * ratio
            val position = Vector3d(startRadius).rotateY(rotation).add(center)
            val tangent = Vector3d(direction.startTangent).rotateY(rotation).normalize()
            Curve.RawPoint(position, tangent, Vector3d(0.0, 1.0, 0.0), arc)
        }
        return PrimeCurve.fromPoints(points)
    }
}
