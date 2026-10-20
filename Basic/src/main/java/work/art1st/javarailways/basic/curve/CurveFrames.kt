package work.art1st.javarailways.basic.curve

import org.joml.Vector3d
import org.joml.Vector3dc
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Shared frame geometry. Inputs are borrowed; every returned vector is a new value. */
internal object CurveFrames {
    /**
     * Gram-Schmidt frame (tangent, normal, binormal), with binormal = normal × tangent.
     * Projects world up onto the tangent's perpendicular plane, using Z near vertical.
     * Callers supply a usable nonzero tangent, as required by curve construction.
     */
    fun computeFrame(tangent3d: Vector3dc): Triple<Vector3d, Vector3d, Vector3d> {
        val t = Vector3d(tangent3d).normalize()
        val ref = if (abs(t.y()) > 0.99) Vector3d(0.0, 0.0, 1.0) else Vector3d(0.0, 1.0, 0.0)
        val n = Vector3d(ref).sub(Vector3d(t).mul(ref.dot(t))).normalize()
        val b = Vector3d()
        n.cross(t, b).normalize()
        return Triple(t, n, b)
    }

    /** Rodrigues rotation about the tangent; the angle is in radians. Never mutates either input. */
    fun applyRollRadians(baseNormal: Vector3dc, tangent: Vector3dc, rollRadians: Double): Vector3d {
        val normal = Vector3d(baseNormal)
        if (abs(rollRadians) < 1e-12) return normal
        val axis = Vector3d(tangent).normalize()
        val cosR = cos(rollRadians)
        val sinR = sin(rollRadians)
        val cross = Vector3d(axis).cross(normal)
        val axisPart = Vector3d(axis).mul(axis.dot(normal) * (1.0 - cosR))
        return normal.mul(cosR).add(cross.mul(sinR)).add(axisPart).normalize()
    }
}
