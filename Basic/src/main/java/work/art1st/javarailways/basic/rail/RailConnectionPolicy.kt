package work.art1st.javarailways.basic.rail

import org.joml.Vector3d
import work.art1st.javarailways.core.physics.rail.RailView
import kotlin.math.acos
import kotlin.math.cos

object RailConnectionPolicy {
    const val DEFAULT_MAX_TANGENT_ANGLE_DEGREES: Double = 30.0

    private const val TANGENT_LENGTH_EPSILON = 1e-12
    private const val DOT_EPSILON = 1e-9
    private const val DEFAULT_ACTIVE_SELECTION_LOOKAHEAD_METERS = 2.0

    fun canTransition(
        fromView: RailView,
        nextView: RailView,
        maxTangentAngleDegrees: Double = DEFAULT_MAX_TANGENT_ANGLE_DEGREES
    ): Boolean {
        val dot = tangentDot(fromView, nextView) ?: return false
        val minDot = cos(Math.toRadians(maxTangentAngleDegrees.coerceIn(0.0, 180.0)))
        return dot + DOT_EPSILON >= minDot
    }

    fun transitionAngleDegrees(fromView: RailView, nextView: RailView): Double? {
        val dot = tangentDot(fromView, nextView) ?: return null
        return Math.toDegrees(acos(dot.coerceIn(-1.0, 1.0)))
    }

    fun activeSelectionScoreDegrees(
        fromView: RailView,
        nextView: RailView,
        lookAheadMeters: Double = DEFAULT_ACTIVE_SELECTION_LOOKAHEAD_METERS
    ): Double? {
        val tangentAngle = transitionAngleDegrees(fromView, nextView) ?: return null
        val chordAngle = lookAheadChordAngleDegrees(fromView, nextView, lookAheadMeters) ?: return tangentAngle
        return maxOf(tangentAngle, chordAngle)
    }

    private fun lookAheadChordAngleDegrees(fromView: RailView, nextView: RailView, lookAheadMeters: Double): Double? {
        val fromTangent = Vector3d(fromView.endPoint.tangent)
        if (fromTangent.lengthSquared() <= TANGENT_LENGTH_EPSILON) return null
        val distance = lookAheadMeters.coerceAtMost(nextView.curve.totalArcLength)
        if (distance <= TANGENT_LENGTH_EPSILON) return null
        val chord = Vector3d(nextView.curve.getPointAt(distance).position).sub(nextView.startPoint.position)
        if (chord.lengthSquared() <= TANGENT_LENGTH_EPSILON) return null
        val dot = fromTangent.normalize().dot(chord.normalize()).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(dot))
    }

    fun tangentDot(fromView: RailView, nextView: RailView): Double? {
        val fromTangent = Vector3d(fromView.endPoint.tangent)
        val nextTangent = Vector3d(nextView.startPoint.tangent)
        if (fromTangent.lengthSquared() <= TANGENT_LENGTH_EPSILON ||
            nextTangent.lengthSquared() <= TANGENT_LENGTH_EPSILON) {
            return null
        }
        return fromTangent.normalize().dot(nextTangent.normalize()).coerceIn(-1.0, 1.0)
    }
}
