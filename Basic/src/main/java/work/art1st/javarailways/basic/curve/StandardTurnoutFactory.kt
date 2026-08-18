package work.art1st.javarailways.basic.curve

import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.util.annotation.Heavy
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ceil

data class StandardTurnoutGeometry(
    val number: Int,
    val radiusMeters: Double,
    val divergingSpeedKmH: Double
) {
    init {
        require(number > 0)
        require(radiusMeters.isFinite() && radiusMeters > 0.0)
        require(divergingSpeedKmH.isFinite() && divergingSpeedKmH > 0.0)
    }

    val branchAngleRadians: Double get() = atan(1.0 / number)
    val displayName: String get() = "${number}号 (R${radiusMeters.toInt()}m / ${divergingSpeedKmH.toInt()}km/h)"
}

enum class StandardTurnout(val number: Int) {
    N4(4),
    N5(5),
    N6(6),
    N7(7),
    N8(8),
    N9(9),
    N10(10),
    N12(12),
    N14(14),
    N16(16),
    N18(18),
    N20(20),
    N24(24),
    N30(30),
    N38(38),
    N42(42),
    N60(60)
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
        val directionSign: Double,
        val turnSign: Double
    )

    /**
     * Builds the diverging route of a standard turnout. The two main-line directions and two
     * branch sides produce four valid end directions; the one closest to [towards] is selected.
     */
    @Heavy
    fun build(
        anchor: Anchor,
        towards: Vector3dc,
        geometry: StandardTurnoutGeometry,
        sampleStepMeters: Double = RailGeometryParameters().turnoutStepMeters
    ): StandardTurnoutBuildResult? {
        require(sampleStepMeters.isFinite() && sampleStepMeters > 0.0)
        val axis = Vector3d(anchor.tangent.x(), 0.0, anchor.tangent.z())
        val target = Vector3d(towards.x(), 0.0, towards.z())
        if (axis.lengthSquared() < 1.0e-12 || target.lengthSquared() < 1.0e-12) return null
        axis.normalize()
        target.normalize()

        val angle = geometry.branchAngleRadians
        val candidates = listOf(1.0, -1.0).flatMap { directionSign ->
            val start = Vector3d(axis).mul(directionSign)
            listOf(1.0, -1.0).map { turnSign ->
                DirectionCandidate(
                    startTangent = start,
                    endTangent = Vector3d(start).rotateY(turnSign * angle).normalize(),
                    directionSign = directionSign,
                    turnSign = turnSign
                )
            }
        }
        val selected = candidates.maxBy { it.endTangent.dot(target) }
        val anchorHorizontal = kotlin.math.hypot(anchor.tangent.x(), anchor.tangent.z())
        val representedGrade = anchor.tangent.y() / anchorHorizontal * selected.directionSign
        val curve = circularBranch(
            anchor.position,
            selected,
            geometry.radiusMeters,
            angle,
            representedGrade,
            sampleStepMeters
        )
        return StandardTurnoutBuildResult(
            curve,
            Vector3d(selected.startTangent.x(), representedGrade, selected.startTangent.z()).normalize(),
            Vector3d(selected.endTangent.x(), representedGrade, selected.endTangent.z()).normalize()
        )
    }

    private fun circularBranch(
        startPosition: Vector3dc,
        direction: DirectionCandidate,
        radius: Double,
        angle: Double,
        grade: Double,
        sampleStepMeters: Double
    ): PrimeCurve {
        val horizontalLength = radius * angle
        val sampleCount = maxOf(2, ceil(horizontalLength / sampleStepMeters).toInt())
        val centerDirection = Vector3d(direction.startTangent).rotateY(direction.turnSign * PI / 2.0)
        val center = Vector3d(startPosition).fma(radius, centerDirection)
        val startRadius = Vector3d(startPosition).sub(center).also { it.y = 0.0 }
        val points = ArrayList<Curve.RawPoint>(sampleCount + 1)
        val slopeLengthFactor = kotlin.math.sqrt(1.0 + grade * grade)
        for (index in 0..sampleCount) {
            val ratio = index.toDouble() / sampleCount
            val horizontalArc = horizontalLength * ratio
            val rotation = direction.turnSign * angle * ratio
            val position = Vector3d(startRadius).rotateY(rotation).add(center).also {
                it.y = startPosition.y() + grade * horizontalArc
            }
            val cumulativeArc = horizontalArc * slopeLengthFactor
            val horizontalTangent = Vector3d(direction.startTangent).rotateY(rotation).normalize()
            val tangent = Vector3d(horizontalTangent.x(), grade, horizontalTangent.z()).normalize()
            val (_, normal, _) = ConstraintSolver.computeFrame(tangent)
            points += Curve.RawPoint(position, tangent, normal, cumulativeArc)
        }
        return PrimeCurve.fromPoints(points)
    }
}
