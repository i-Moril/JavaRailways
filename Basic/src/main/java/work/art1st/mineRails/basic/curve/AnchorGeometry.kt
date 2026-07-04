package work.art1st.mineRails.basic.curve

import org.joml.Vector3d
import org.joml.Vector3dc
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

object AnchorGeometry {
    const val ROLL_METADATA_KEY: String = "rollDegrees"

    fun formatNumber(value: Double, digits: Int = 3): String =
        "% .${digits}f".format(java.util.Locale.US, value).trim()

    fun rollDegrees(anchor: Anchor): Double =
        anchor.metadata[ROLL_METADATA_KEY]?.toDoubleOrNull() ?: 0.0

    fun withRoll(anchor: Anchor, rollDegrees: Double): Anchor =
        anchor.copy(metadata = anchor.metadata + (ROLL_METADATA_KEY to formatNumber(rollDegrees, digits = 3)))

    fun yawDegrees(tangent: Vector3dc): Double {
        val direction = Vector3d(tangent)
        if (direction.lengthSquared() < 1e-9) return 0.0
        direction.normalize()
        return Math.toDegrees(atan2(-direction.x(), direction.z()))
    }

    fun pitchDegrees(tangent: Vector3dc): Double {
        val direction = Vector3d(tangent)
        if (direction.lengthSquared() < 1e-9) return 0.0
        direction.normalize()
        return Math.toDegrees(asin(direction.y().coerceIn(-1.0, 1.0)))
    }

    fun tangentFromYawPitch(yawDegrees: Double, pitchDegrees: Double): Vector3d {
        val yawRad = Math.toRadians(yawDegrees)
        val pitchRad = Math.toRadians(pitchDegrees.coerceIn(-89.9, 89.9))
        val horizontal = cos(pitchRad)
        return Vector3d(
            -sin(yawRad) * horizontal,
            sin(pitchRad),
            cos(yawRad) * horizontal
        ).normalize()
    }

    fun rollDegreesFromNormal(tangent: Vector3dc, normal: Vector3dc): Double {
        val t = Vector3d(tangent)
        val n = Vector3d(normal)
        if (t.lengthSquared() < 1e-12 || n.lengthSquared() < 1e-12) return 0.0
        t.normalize()
        n.normalize()
        val (_, baseNormal, _) = ConstraintSolver.computeFrame(t)
        val sinRoll = Vector3d(baseNormal).cross(n).dot(t)
        val cosRoll = baseNormal.dot(n)
        return Math.toDegrees(atan2(sinRoll, cosRoll))
    }
}
