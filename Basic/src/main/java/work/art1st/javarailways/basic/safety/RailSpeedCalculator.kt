package work.art1st.javarailways.basic.safety

import org.joml.Vector3d
import work.art1st.javarailways.basic.curve.CurveFrames
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.util.annotation.Heavy
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

data class RailSpeedPhysics(
    val gaugeMeters: Double = RailSpeedCalculator.STANDARD_GAUGE_METERS,
    val gravityMetersPerSecondSquared: Double = RailSpeedCalculator.GRAVITY_METERS_PER_SECOND_SQUARED
) {
    init {
        require(gaugeMeters.isFinite() && gaugeMeters > 0.0)
        require(gravityMetersPerSecondSquared.isFinite() && gravityMetersPerSecondSquared > 0.0)
    }
}

/** Geometry-derived operating and derail speed thresholds. */
data class RailSpeedLimits(
    val operatingLimitMetersPerSecond: Double,
    /** Null for geometry without meaningful horizontal curvature. */
    val derailSpeedMetersPerSecond: Double?
)

object RailSpeedCalculator {
    const val STANDARD_GAUGE_METERS: Double = 1.435
    const val GRAVITY_METERS_PER_SECOND_SQUARED: Double = 9.80665

    @Heavy
    fun calculate(
        curve: PrimeCurve,
        requestedLimitMetersPerSecond: Double,
        allowableCantDeficiencyMeters: Double,
        derailLateralAccelerationMetersPerSecondSquared: Double,
        physics: RailSpeedPhysics = RailSpeedPhysics()
    ): RailSpeedLimits {
        require(requestedLimitMetersPerSecond > 0.0)
        require(allowableCantDeficiencyMeters >= 0.0)
        require(derailLateralAccelerationMetersPerSecondSquared > 0.0)

        val points = curve.stream().toList()
            .distinctBy { it.arcLength }
        if (points.size < 2) {
            return RailSpeedLimits(requestedLimitMetersPerSecond, null)
        }

        val operatingAcceleration = physics.gravityMetersPerSecondSquared *
            allowableCantDeficiencyMeters / physics.gaugeMeters
        var geometryOperatingLimit = Double.POSITIVE_INFINITY
        var geometryDerailLimit = Double.POSITIVE_INFINITY

        points.indices.forEach { index ->
            val previous = points[if (index == 0) 0 else index - 1]
            val next = points[if (index == points.lastIndex) points.lastIndex else index + 1]
            val deltaArc = next.arcLength - previous.arcLength
            if (deltaArc <= 1.0e-9) return@forEach

            val previousTangent = horizontalUnit(previous.tangent) ?: return@forEach
            val nextTangent = horizontalUnit(next.tangent) ?: return@forEach
            val crossY = previousTangent.z() * nextTangent.x() - previousTangent.x() * nextTangent.z()
            val turnAngle = atan2(crossY, previousTangent.dot(nextTangent))
            val signedCurvature = turnAngle / deltaArc
            val curvature = kotlin.math.abs(signedCurvature)
            if (curvature < 1.0e-8) return@forEach

            val point = points[index]
            val tangent = Vector3d(point.tangent).normalize()
            val normal = Vector3d(point.normal).normalize()
            val (_, baseNormal, _) = CurveFrames.computeFrame(tangent)
            val roll = atan2(Vector3d(baseNormal).cross(normal).dot(tangent), baseNormal.dot(normal))
            val supportingCantAngle = -sign(signedCurvature) * roll
            val denominator = curvature * cos(supportingCantAngle)
            if (denominator <= 1.0e-12) return@forEach
            val cantAcceleration = physics.gravityMetersPerSecondSquared * sin(supportingCantAngle)

            val operating = sqrt(((operatingAcceleration + cantAcceleration) / denominator).coerceAtLeast(0.0))
            val derail = sqrt(((derailLateralAccelerationMetersPerSecondSquared + cantAcceleration) / denominator).coerceAtLeast(0.0))
            geometryOperatingLimit = min(geometryOperatingLimit, operating)
            geometryDerailLimit = min(geometryDerailLimit, derail)
        }

        return RailSpeedLimits(
            operatingLimitMetersPerSecond = min(requestedLimitMetersPerSecond, geometryOperatingLimit),
            derailSpeedMetersPerSecond = geometryDerailLimit.takeIf { it.isFinite() }
        )
    }

    private fun horizontalUnit(vector: org.joml.Vector3dc): Vector3d? {
        val horizontal = Vector3d(vector.x(), 0.0, vector.z())
        if (horizontal.lengthSquared() < 1.0e-12) return null
        return horizontal.normalize()
    }
}
