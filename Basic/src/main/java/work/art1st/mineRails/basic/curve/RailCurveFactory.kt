package work.art1st.mineRails.basic.curve

import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.api.physics.curve.PrimeCurve
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class RailCurveBuildResult(
    val curve: PrimeCurve?,
    val error: String? = null
) {
    val isSuccess: Boolean get() = curve != null

    companion object {
        fun success(curve: PrimeCurve): RailCurveBuildResult = RailCurveBuildResult(curve)
        fun failure(error: String): RailCurveBuildResult = RailCurveBuildResult(null, error)
    }
}

/**
 * Builds Core PrimeCurve geometry from editor anchors.
 *
 * This contains no Bukkit state and is shared by RailWand create/connect/edit flows.
 */
object RailCurveFactory {
    fun build(from: Anchor, to: Anchor, radius: Double = 0.0): RailCurveBuildResult {
        val fromPos = Vector3d(from.position)
        val toPos = Vector3d(to.position)
        val chord = Vector3d(toPos).sub(fromPos)
        val fromFlipped = shouldFlipTangentAlongChord(from.tangent, chord)
        val toFlipped = shouldFlipTangentAlongChord(to.tangent, chord)
        val fromTan3d = orientedTangent(from.tangent, fromFlipped).normalize()
        val toTan3d = orientedTangent(to.tangent, toFlipped).normalize()
        val fromRoll = if (fromFlipped) -AnchorGeometry.rollDegrees(from) else AnchorGeometry.rollDegrees(from)
        val toRoll = if (toFlipped) -AnchorGeometry.rollDegrees(to) else AnchorGeometry.rollDegrees(to)

        val fromTan2d = Vector3d(fromTan3d.x(), 0.0, fromTan3d.z()).normalize()
        val toTan2d = Vector3d(toTan3d.x(), 0.0, toTan3d.z()).normalize()
        val angle = ConstraintSolver.computeAngle(fromTan2d, toTan2d)

        if (angle < 1e-6) {
            if (!isChordAlignedWithTangent(chord, fromTan2d)) {
                return RailCurveBuildResult.failure("同向端点不共线，需要 S 弯模板")
            }
            val horizontalDistance = Vector3d(chord.x(), 0.0, chord.z()).length()
            if (horizontalDistance < 1e-6) return RailCurveBuildResult.failure("端点距离过短")
            val direction = Vector3d(chord.x(), 0.0, chord.z()).normalize()
            val curve = createStraightCurve(fromPos, toPos, direction, horizontalDistance, fromTan3d, toTan3d, fromRoll, toRoll)
            if (!validateGeneratedCurve(curve, fromPos, toPos, fromTan3d, toTan3d, fromRoll, toRoll)) {
                return RailCurveBuildResult.failure("直线模板生成后端点位置/切线/roll 校验失败")
            }
            return RailCurveBuildResult.success(curve)
        }

        val result: ConstraintSolveResult = if (radius > 0) {
            ConstraintSolver.solve(fromPos, fromTan2d, toPos, toTan2d, radius)
        } else {
            ConstraintSolver.solveBest(fromPos, fromTan2d, toPos, toTan2d)
        }
        if (result !is ConstraintSolveResult.Success) {
            return RailCurveBuildResult.failure((result as ConstraintSolveResult.Failure).reason)
        }

        val curvePoints = ConstraintSolver.generateCurve(result, fromPos, fromTan3d, toPos, toTan3d, 0.2, fromRoll, toRoll)
        val curve = PrimeCurve.fromPoints(curvePoints)
        if (!validateGeneratedCurve(curve, fromPos, toPos, fromTan3d, toTan3d, fromRoll, toRoll)) {
            return RailCurveBuildResult.failure("五段式模板生成后端点位置/切线/roll 校验失败")
        }
        return RailCurveBuildResult.success(curve)
    }

    private fun shouldFlipTangentAlongChord(tangent: Vector3dc, chord: Vector3dc): Boolean {
        val tangentVector = Vector3d(tangent)
        if (tangentVector.lengthSquared() < 1e-12) return false
        val tangentHorizontal = Vector3d(tangentVector.x(), 0.0, tangentVector.z())
        val chordHorizontal = Vector3d(chord.x(), 0.0, chord.z())
        val dot = if (tangentHorizontal.lengthSquared() > 1e-12 && chordHorizontal.lengthSquared() > 1e-12) {
            tangentHorizontal.dot(chordHorizontal)
        } else {
            tangentVector.dot(chord)
        }
        return dot < 0.0
    }

    private fun orientedTangent(tangent: Vector3dc, flipped: Boolean): Vector3d =
        Vector3d(tangent).also { if (flipped) it.negate() }

    private fun isChordAlignedWithTangent(chord: Vector3dc, tangent2d: Vector3dc): Boolean {
        val chord2d = Vector3d(chord.x(), 0.0, chord.z())
        if (chord2d.lengthSquared() < 1e-12) return false
        val chordDir = chord2d.normalize()
        val tangentDir = Vector3d(tangent2d)
        if (tangentDir.lengthSquared() < 1e-12) return false
        tangentDir.normalize()
        return chordDir.dot(tangentDir) >= cos(Math.toRadians(1.0))
    }

    private fun validateGeneratedCurve(
        curve: PrimeCurve,
        fromPos: Vector3dc,
        toPos: Vector3dc,
        fromTangent: Vector3dc,
        toTangent: Vector3dc,
        fromRollDegrees: Double = 0.0,
        toRollDegrees: Double = 0.0
    ): Boolean {
        val start = curve.getPointAt(0.0)
        val end = curve.getPointAt(curve.totalArcLength)

        val positionTolerance = Curve.TOLERANCE * 2
        val startDistance = start.position.distance(fromPos)
        val endDistance = end.position.distance(toPos)
        if (startDistance > positionTolerance || endDistance > positionTolerance) return false

        fun tangentDot(actual: Vector3dc, expected: Vector3dc): Double? {
            val a = Vector3d(actual)
            val e = Vector3d(expected)
            if (a.lengthSquared() < 1e-12 || e.lengthSquared() < 1e-12) return null
            return a.normalize().dot(e.normalize())
        }

        val tangentToleranceDot = cos(Math.toRadians(1.0))
        val startDot = tangentDot(start.tangent, fromTangent) ?: return false
        val endDot = tangentDot(end.tangent, toTangent) ?: return false
        if (startDot < tangentToleranceDot || endDot < tangentToleranceDot) return false

        fun normalMatches(actual: Vector3dc, tangent: Vector3dc, rollDegrees: Double): Boolean {
            val (_, baseNormal, _) = ConstraintSolver.computeFrame(tangent)
            val expected = applyRollToNormal(baseNormal, tangent, rollDegrees)
            val a = Vector3d(actual)
            if (a.lengthSquared() < 1e-12) return false
            return a.normalize().dot(expected) >= tangentToleranceDot
        }

        return normalMatches(start.normal, fromTangent, fromRollDegrees) &&
            normalMatches(end.normal, toTangent, toRollDegrees)
    }

    private fun createStraightCurve(
        from: Vector3dc,
        to: Vector3dc,
        horizontalDirection: Vector3dc,
        length: Double,
        fromTangent: Vector3dc,
        toTangent: Vector3dc,
        fromRollDegrees: Double = 0.0,
        toRollDegrees: Double = 0.0
    ): PrimeCurve {
        val dir = Vector3d(horizontalDirection).normalize()
        val hLenA = sqrt(fromTangent.x() * fromTangent.x() + fromTangent.z() * fromTangent.z())
        val hLenB = sqrt(toTangent.x() * toTangent.x() + toTangent.z() * toTangent.z())
        val gA = if (hLenA > 1e-10) fromTangent.y() / hLenA else 0.0
        val gB = if (hLenB > 1e-10) toTangent.y() / hLenB else 0.0
        val dy = to.y() - from.y()
        val u = dy - gA * length
        val v = gB - gA
        val c = if (length > 1e-6) (3.0 * u - v * length) / (length * length) else 0.0
        val d = if (length > 1e-6) (v * length - 2.0 * u) / (length * length * length) else 0.0

        fun heightAt(arc: Double): Double = from.y() + gA * arc + c * arc * arc + d * arc * arc * arc
        fun gradeAt(arc: Double): Double = gA + 2.0 * c * arc + 3.0 * d * arc * arc
        fun rollAt(arc: Double): Double {
            val t = if (length > 1e-9) (arc / length).coerceIn(0.0, 1.0) else 0.0
            return fromRollDegrees + (toRollDegrees - fromRollDegrees) * t
        }

        val points = mutableListOf<Curve.RawPoint>()
        val step = 0.2
        var arc = 0.0
        while (arc < length - 1e-9) {
            points.add(straightCurvePoint(from, dir, arc, heightAt(arc), gradeAt(arc), rollAt(arc)))
            arc += minOf(step, length - arc)
        }
        points.add(straightCurvePoint(from, dir, length, to.y(), gB, toRollDegrees))
        return PrimeCurve.fromPoints(points)
    }

    private fun straightCurvePoint(
        from: Vector3dc,
        horizontalDirection: Vector3dc,
        arc: Double,
        y: Double,
        grade: Double,
        rollDegrees: Double
    ): Curve.RawPoint {
        val dir = Vector3d(horizontalDirection).normalize()
        val position = Vector3d(from).fma(arc, dir)
        position.y = y
        val tangent = Vector3d(dir.x(), grade, dir.z()).normalize()
        val (_, baseNormal, _) = ConstraintSolver.computeFrame(tangent)
        val normal = applyRollToNormal(baseNormal, tangent, rollDegrees)
        return Curve.RawPoint(position, tangent, normal, arc)
    }

    fun applyRollToNormal(baseNormal: Vector3dc, tangent: Vector3dc, rollDegrees: Double): Vector3d {
        val rollRadians = Math.toRadians(rollDegrees)
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
