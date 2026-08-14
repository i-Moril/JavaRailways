package work.art1st.javarailways.tunnelbridgebuilder.algorithm

import kotlin.math.sqrt

/** Minimal immutable vector used by the platform-independent sweep planner. */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "Vector components must be finite" }
    }

    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    operator fun times(scale: Double) = Vec3(x * scale, y * scale, z * scale)

    infix fun dot(other: Vec3): Double = x * other.x + y * other.y + z * other.z

    infix fun cross(other: Vec3) = Vec3(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x
    )

    fun lengthSquared(): Double = this dot this
    fun length(): Double = sqrt(lengthSquared())

    fun normalized(): Vec3 {
        val length = length()
        require(length > EPSILON) { "Cannot normalize a zero-length vector" }
        return this * (1.0 / length)
    }

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        val UNIT_X = Vec3(1.0, 0.0, 0.0)
        val UNIT_Y = Vec3(0.0, 1.0, 0.0)
        val UNIT_Z = Vec3(0.0, 0.0, 1.0)

        private const val EPSILON = 1.0e-12
    }
}

/** One center-line sample with an orthonormal forward/up/right frame. */
class SweepFrame(position: Vec3, tangent: Vec3, normal: Vec3) {
    val position: Vec3 = position
    val tangent: Vec3 = tangent.normalized()
    val normal: Vec3
    val right: Vec3

    init {
        val projectedNormal = normal - this.tangent * (normal dot this.tangent)
        this.normal = projectedNormal.normalized()
        // Section columns increase toward the local right side.
        this.right = (this.normal cross this.tangent).normalized()
    }
}

internal data class NearestCenterLinePoint(
    val frame: SweepFrame,
    val distanceSquared: Double
)
