package work.art1st.javarailways.basic.curve

import org.joml.Vector3d
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.util.annotation.Heavy
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.tan

/** Actual values measured from final immutable rail geometry. */
data class RailGeometryMeasurements(
    val minimumHorizontalRadiusMeters: Double?,
    val maximumSuperelevationMeters: Double,
    /** Maximum absolute rise/run ratio sampled from the final curve tangents. */
    val maximumAbsoluteGrade: Double
)

object RailGeometryMeasurementService {
    @Heavy
    fun measure(
        curves: Iterable<PrimeCurve>,
        gaugeMeters: Double
    ): RailGeometryMeasurements {
        require(gaugeMeters.isFinite() && gaugeMeters > 0.0)
        var maximumCurvature = 0.0
        var maximumSuperelevation = 0.0
        var maximumAbsoluteGrade = 0.0
        curves.forEach { curve ->
            val points = curve.stream().toList().distinctBy { it.arcLength }
            points.indices.forEach { index ->
                val point = points[index]
                val tangent = Vector3d(point.tangent)
                val normal = Vector3d(point.normal)
                if (tangent.lengthSquared() > 1.0e-12) {
                    val horizontalLength = hypot(tangent.x(), tangent.z())
                    if (horizontalLength > 1.0e-12) {
                        val grade = abs(tangent.y() / horizontalLength)
                        if (grade.isFinite()) maximumAbsoluteGrade = maxOf(maximumAbsoluteGrade, grade)
                    }
                }
                if (tangent.lengthSquared() > 1.0e-12 && normal.lengthSquared() > 1.0e-12) {
                    tangent.normalize()
                    normal.normalize()
                    val (_, baseNormal, _) = ConstraintSolver.computeFrame(tangent)
                    val roll = atan2(
                        Vector3d(baseNormal).cross(normal).dot(tangent),
                        baseNormal.dot(normal)
                    )
                    val height = abs(tan(roll) * gaugeMeters)
                    if (height.isFinite()) maximumSuperelevation = maxOf(maximumSuperelevation, height)
                }

                val previous = points[if (index == 0) 0 else index - 1]
                val next = points[if (index == points.lastIndex) points.lastIndex else index + 1]
                val deltaArc = next.arcLength - previous.arcLength
                if (deltaArc <= 1.0e-9) return@forEach
                val previousHorizontal = horizontalUnit(previous.tangent) ?: return@forEach
                val nextHorizontal = horizontalUnit(next.tangent) ?: return@forEach
                val crossY = previousHorizontal.z() * nextHorizontal.x() -
                    previousHorizontal.x() * nextHorizontal.z()
                val angle = atan2(crossY, previousHorizontal.dot(nextHorizontal))
                maximumCurvature = maxOf(maximumCurvature, abs(angle / deltaArc))
            }
        }
        return RailGeometryMeasurements(
            minimumHorizontalRadiusMeters = maximumCurvature
                .takeIf { it >= 1.0e-8 }
                ?.let { 1.0 / it },
            maximumSuperelevationMeters = maximumSuperelevation,
            maximumAbsoluteGrade = maximumAbsoluteGrade
        )
    }

    private fun horizontalUnit(vector: org.joml.Vector3dc): Vector3d? {
        val horizontal = Vector3d(vector.x(), 0.0, vector.z())
        if (horizontal.lengthSquared() < 1.0e-12) return null
        return horizontal.normalize()
    }
}
