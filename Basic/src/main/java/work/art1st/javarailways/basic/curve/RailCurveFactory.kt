package work.art1st.javarailways.basic.curve

import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.util.annotation.Heavy
import work.art1st.javarailways.core.api.threading.ThreadGuard
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos

enum class RailCurveTemplateType(val displayName: String) {
    AUTO("自动"),
    STRAIGHT("直线"),
    TWO_POINT_STRAIGHT("两点直线"),
    FIVE_SEGMENT("五段式曲线"),
    S_CURVE("S弯")
}

data class RailCurveBuildOptions(
    val template: RailCurveTemplateType = RailCurveTemplateType.AUTO,
    val radius: Double = 0.0,
    val superelevation: SuperelevationConfig? = null,
    val geometry: RailGeometryParameters = RailGeometryParameters()
)

data class CurveTemplateMatch(
    val template: RailCurveTemplateType,
    val reason: String
)

sealed class RailCurveBuildResult {
    /** Non-null only for [Success]; prefer an `is Success` check for exhaustive handling. */
    abstract val curve: PrimeCurve?
    abstract val error: String?
    abstract val selectedTemplate: RailCurveTemplateType?
    abstract val selectedReason: String?

    val isSuccess: Boolean get() = this is Success

    open val logicalBreakFractions: List<Double>
        get() = (this as? Success)?.logicalBreakFractions.orEmpty()

    data class Success(
        override val curve: PrimeCurve,
        override val selectedTemplate: RailCurveTemplateType,
        override val selectedReason: String? = null,
        /** Internal player-invisible boundaries as fractions of total arc length. */
        override val logicalBreakFractions: List<Double> = emptyList()
    ) : RailCurveBuildResult() {
        override val error: String? get() = null
    }

    data class Failure(
        override val error: String,
        override val selectedTemplate: RailCurveTemplateType? = null,
        override val selectedReason: String? = null
    ) : RailCurveBuildResult() {
        override val curve: PrimeCurve? get() = null
    }

    companion object {
        fun success(
            curve: PrimeCurve,
            template: RailCurveTemplateType,
            reason: String? = null,
            logicalBreakFractions: List<Double> = emptyList()
        ): RailCurveBuildResult = Success(curve, template, reason, logicalBreakFractions)

        fun failure(
            error: String,
            template: RailCurveTemplateType? = null,
            reason: String? = null
        ): RailCurveBuildResult = Failure(error, template, reason)
    }
}

sealed class RailParallelCurveBuildResult {
    /** Non-null only for [Success]; prefer an `is Success` check for exhaustive handling. */
    abstract val curves: List<PrimeCurve>?
    abstract val error: String?
    abstract val selectedTemplate: RailCurveTemplateType?
    abstract val selectedReason: String?

    val isSuccess: Boolean get() = this is Success

    open val logicalBreakFractionsByLane: List<List<Double>>
        get() = (this as? Success)?.logicalBreakFractionsByLane.orEmpty()

    data class Success(
        override val curves: List<PrimeCurve>,
        override val selectedTemplate: RailCurveTemplateType,
        override val selectedReason: String? = null,
        override val logicalBreakFractionsByLane: List<List<Double>> = curves.map { emptyList() }
    ) : RailParallelCurveBuildResult() {
        override val error: String? get() = null
    }

    data class Failure(
        override val error: String,
        override val selectedTemplate: RailCurveTemplateType? = null,
        override val selectedReason: String? = null
    ) : RailParallelCurveBuildResult() {
        override val curves: List<PrimeCurve>? get() = null
    }

    companion object {
        fun success(
            curves: List<PrimeCurve>,
            template: RailCurveTemplateType,
            reason: String? = null,
            logicalBreakFractionsByLane: List<List<Double>> = curves.map { emptyList() }
        ): RailParallelCurveBuildResult = Success(curves, template, reason, logicalBreakFractionsByLane)

        fun failure(
            error: String,
            template: RailCurveTemplateType? = null,
            reason: String? = null
        ): RailParallelCurveBuildResult = Failure(error, template, reason)
    }
}

/**
 * Builds Core PrimeCurve geometry from editor anchors.
 *
 * This contains no Bukkit state and is shared by RailWand create/connect/edit flows.
 */
object RailCurveFactory {
    fun build(from: Anchor, to: Anchor, radius: Double = 0.0): RailCurveBuildResult =
        build(from, to, RailCurveBuildOptions(radius = radius))

    fun build(from: Anchor, to: Anchor, options: RailCurveBuildOptions): RailCurveBuildResult {
        ThreadGuard.ensureOffMainThread("RailCurveFactory.build")
        val prepared = prepareAnchors(from, to)
            ?: return RailCurveBuildResult.failure("端点切线或距离无效")
        val match = when (options.template) {
            RailCurveTemplateType.AUTO -> classify(prepared)
            else -> CurveTemplateMatch(options.template, "用户指定模板: ${options.template.displayName}")
        }

        return when (match.template) {
            RailCurveTemplateType.STRAIGHT,
            RailCurveTemplateType.TWO_POINT_STRAIGHT -> buildStraight(prepared, options, match)
            RailCurveTemplateType.FIVE_SEGMENT -> buildFiveSegment(prepared, options, match)
            RailCurveTemplateType.S_CURVE -> buildSCurve(prepared, options, match)
            RailCurveTemplateType.AUTO -> error("AUTO should be resolved before build")
        }
    }

    fun buildParallel(fromAnchors: List<Anchor>, toAnchors: List<Anchor>, options: RailCurveBuildOptions): RailParallelCurveBuildResult {
        ThreadGuard.ensureOffMainThread("RailCurveFactory.buildParallel")
        if (fromAnchors.isEmpty()) return RailParallelCurveBuildResult.failure("并行曲线至少需要 1 组锚点")
        if (fromAnchors.size != toAnchors.size) return RailParallelCurveBuildResult.failure("并行曲线起终点数量不一致")
        if (fromAnchors.size == 1) {
            return when (val result = build(fromAnchors.single(), toAnchors.single(), options)) {
                is RailCurveBuildResult.Success -> RailParallelCurveBuildResult.Success(
                    listOf(result.curve),
                    result.selectedTemplate,
                    result.selectedReason,
                    listOf(result.logicalBreakFractions)
                )
                is RailCurveBuildResult.Failure -> RailParallelCurveBuildResult.Failure(
                    result.error, result.selectedTemplate, result.selectedReason
                )
            }
        }

        val centerFrom = ParallelCurveBuilder.centerAnchor("parallel-center-from", fromAnchors)
            ?: return RailParallelCurveBuildResult.failure("无法计算并行起点中心")
        val centerTo = ParallelCurveBuilder.centerAnchor("parallel-center-to", toAnchors)
            ?: return RailParallelCurveBuildResult.failure("无法计算并行终点中心")
        val centerResult = when (val built = build(centerFrom, centerTo, options)) {
            is RailCurveBuildResult.Success -> built
            is RailCurveBuildResult.Failure -> return RailParallelCurveBuildResult.Failure(
                built.error, built.selectedTemplate, built.selectedReason
            )
        }

        return ParallelCurveBuilder.build(fromAnchors, toAnchors, centerFrom, centerTo, centerResult)
    }

    private data class PreparedAnchors(
        val from: Anchor,
        val to: Anchor,
        val fromPos: Vector3d,
        val toPos: Vector3d,
        val chord: Vector3d,
        val fromTan3d: Vector3d,
        val toTan3d: Vector3d,
        val fromRoll: Double,
        val toRoll: Double,
        val fromTan2d: Vector3d,
        val toTan2d: Vector3d,
        val angle: Double
    )

    private fun prepareAnchors(from: Anchor, to: Anchor): PreparedAnchors? {
        val fromPos = Vector3d(from.position)
        val toPos = Vector3d(to.position)
        val chord = Vector3d(toPos).sub(fromPos)
        val chordHorizontal = Vector3d(chord.x(), 0.0, chord.z())
        if (chordHorizontal.lengthSquared() < 1e-12) return null
        val fromFlipped = shouldFlipTangentAlongChord(from.tangent, chord)
        val toFlipped = shouldFlipTangentAlongChord(to.tangent, chord)
        val fromTan3d = orientedTangent(from.tangent, fromFlipped)
        val toTan3d = orientedTangent(to.tangent, toFlipped)
        if (fromTan3d.lengthSquared() < 1e-12 || toTan3d.lengthSquared() < 1e-12) return null
        fromTan3d.normalize()
        toTan3d.normalize()
        val fromTan2d = Vector3d(fromTan3d.x(), 0.0, fromTan3d.z())
        val toTan2d = Vector3d(toTan3d.x(), 0.0, toTan3d.z())
        if (fromTan2d.lengthSquared() < 1e-12 || toTan2d.lengthSquared() < 1e-12) return null
        fromTan2d.normalize()
        toTan2d.normalize()
        return PreparedAnchors(
            from = from,
            to = to,
            fromPos = fromPos,
            toPos = toPos,
            chord = chord,
            fromTan3d = fromTan3d,
            toTan3d = toTan3d,
            fromRoll = if (fromFlipped) -AnchorGeometry.rollDegrees(from) else AnchorGeometry.rollDegrees(from),
            toRoll = if (toFlipped) -AnchorGeometry.rollDegrees(to) else AnchorGeometry.rollDegrees(to),
            fromTan2d = fromTan2d,
            toTan2d = toTan2d,
            angle = ConstraintSolver.computeAngle(fromTan2d, toTan2d)
        )
    }

    private fun classify(prepared: PreparedAnchors): CurveTemplateMatch {
        return if (prepared.angle < 1e-6) {
            if (isChordAlignedWithTangent(prepared.chord, prepared.fromTan2d)) {
                CurveTemplateMatch(RailCurveTemplateType.STRAIGHT, "两端水平切线同向且 chord 横向偏移在直线容差内")
            } else {
                CurveTemplateMatch(RailCurveTemplateType.S_CURVE, "两端水平切线同向但 chord 存在横向偏移")
            }
        } else {
            CurveTemplateMatch(RailCurveTemplateType.FIVE_SEGMENT, "两端切线存在夹角")
        }
    }

    private fun buildStraight(
        prepared: PreparedAnchors,
        options: RailCurveBuildOptions,
        match: CurveTemplateMatch
    ): RailCurveBuildResult {
        if (options.radius > 0.0) {
            return RailCurveBuildResult.failure("直线模板不能使用有限的精确半径", match.template, match.reason)
        }
        val geometry = options.geometry
        val length = prepared.chord.length()
        if (length < 1e-6) {
            return RailCurveBuildResult.failure("端点距离过短", match.template, match.reason)
        }

        // 直线模板只约束水平 bearing。纵断面独立通过端点高程和 Pitch 求解，
        // 因而可以在水平投影保持直线的同时形成竖曲线。
        val lateralOffset = horizontalLateralOffset(prepared.chord, prepared.fromTan2d)
        if (lateralOffset > Curve.TOLERANCE) {
            return RailCurveBuildResult.failure("直线终点不在起点切线的水平方向上", match.template, match.reason)
        }
        val tangentDot = prepared.fromTan2d.dot(prepared.toTan2d)
        if (tangentDot < cos(Math.toRadians(0.01))) {
            return RailCurveBuildResult.failure("直线起终点的水平切线方向必须相同", match.template, match.reason)
        }
        val curve = createHorizontallyStraightCurve(
            prepared.fromPos,
            prepared.toPos,
            prepared.fromTan3d,
            prepared.toTan3d,
            prepared.fromRoll,
            prepared.toRoll,
            geometry.defaultStepMeters
        )
        if (!validateGeneratedCurve(
                curve,
                prepared.fromPos,
                prepared.toPos,
                prepared.fromTan3d,
                prepared.toTan3d,
                prepared.fromRoll,
                prepared.toRoll
            )
        ) {
            return RailCurveBuildResult.failure("直线模板生成后端点位置/切线/roll 校验失败", match.template, match.reason)
        }
        return RailCurveBuildResult.success(curve, match.template, match.reason)
    }

    @OptIn(Heavy::class)
    private fun buildFiveSegment(
        prepared: PreparedAnchors,
        options: RailCurveBuildOptions,
        match: CurveTemplateMatch
    ): RailCurveBuildResult {
        if (prepared.angle < 1e-6) {
            return RailCurveBuildResult.failure("五段式曲线要求两端切线存在夹角；同向错位请使用 S 弯模板", match.template, match.reason)
        }
        val result: ConstraintSolveResult = if (options.radius > 0) {
            ConstraintSolver.solve(prepared.fromPos, prepared.fromTan2d, prepared.toPos, prepared.toTan2d, options.radius)
        } else {
            ConstraintSolver.solveBest(
                prepared.fromPos,
                prepared.fromTan2d,
                prepared.toPos,
                prepared.toTan2d,
                options.geometry
            )
        }
        if (result !is ConstraintSolveResult.Success) {
            return RailCurveBuildResult.failure((result as ConstraintSolveResult.Failure).reason, match.template, match.reason)
        }

        val curvePoints = ConstraintSolver.generateCurve(
            result,
            prepared.fromPos,
            prepared.fromTan3d,
            prepared.toPos,
            prepared.toTan3d,
            options.geometry.defaultStepMeters,
            prepared.fromRoll,
            prepared.toRoll,
            options.superelevation,
            options.geometry
        )
        val curve = PrimeCurve.fromPoints(curvePoints)
        if (!validateGeneratedCurve(curve, prepared.fromPos, prepared.toPos, prepared.fromTan3d, prepared.toTan3d, prepared.fromRoll, prepared.toRoll)) {
            return RailCurveBuildResult.failure("五段式模板生成后端点位置/切线/roll 校验失败", match.template, match.reason)
        }
        val samples = curve.stream().toList()
        val horizontalStations = RailCurvePartitioner.horizontalStations(samples)
        val breakFractions = RailCurvePartitioner.mapStations(horizontalStations,
            samples.map { it.arcLength }, listOf(ConstraintSolver.generatedStraightLength(result.straightA),
                horizontalStations.last() - ConstraintSolver.generatedStraightLength(result.straightB)))
            .map { it / curve.totalArcLength }
        return RailCurveBuildResult.success(curve, match.template, match.reason, breakFractions)
    }

    private fun buildSCurve(
        prepared: PreparedAnchors,
        options: RailCurveBuildOptions,
        match: CurveTemplateMatch
    ): RailCurveBuildResult {
        val horizontalDistance = Vector3d(prepared.chord.x(), 0.0, prepared.chord.z()).length()
        if (horizontalDistance < 1e-6) {
            return RailCurveBuildResult.failure("端点距离过短", match.template, match.reason)
        }

        var handle0 = HermiteSCurveBuilder.hermiteHandleLength(
            prepared.chord, prepared.fromTan2d, horizontalDistance, options.geometry
        )
        var handle1 = HermiteSCurveBuilder.hermiteHandleLength(
            prepared.chord, prepared.toTan2d, horizontalDistance, options.geometry
        )
        var minRadius = HermiteSCurveBuilder.estimateHermiteMinRadius(
            prepared.fromPos,
            prepared.toPos,
            prepared.fromTan2d,
            prepared.toTan2d,
            handle0,
            handle1,
            options.geometry.sCurveCurvatureProbeCount
        )
        if (options.radius > 0.0) {
            val fitted = HermiteSCurveBuilder.fitHermiteHandlesToExactRadius(
                prepared.fromPos,
                prepared.toPos,
                prepared.fromTan2d,
                prepared.toTan2d,
                handle0,
                handle1,
                options.radius,
                options.geometry.sCurveCurvatureProbeCount
            )
            handle0 = fitted.first
            handle1 = fitted.second
            minRadius = fitted.third
            val tolerance = maxOf(0.5, options.radius * 0.005)
            if (abs(minRadius - options.radius) > tolerance) {
                return RailCurveBuildResult.failure(
                    "S弯无法满足精确半径: 实际 %.2fm，要求 %.2fm".format(Locale.US, minRadius, options.radius),
                    match.template,
                    match.reason
                )
            }
        }

        val curve = HermiteSCurveBuilder.createHermiteSCurve(
            prepared.fromPos,
            prepared.toPos,
            prepared.fromTan3d,
            prepared.toTan3d,
            prepared.fromTan2d,
            prepared.toTan2d,
            handle0,
            handle1,
            prepared.fromRoll,
            prepared.toRoll,
            horizontalDistance,
            options.superelevation,
            options.geometry
        )
        if (!validateGeneratedCurve(curve, prepared.fromPos, prepared.toPos, prepared.fromTan3d, prepared.toTan3d, prepared.fromRoll, prepared.toRoll)) {
            return RailCurveBuildResult.failure("S弯模板生成后端点位置/切线/roll 校验失败", match.template, match.reason)
        }
        return RailCurveBuildResult.success(curve, match.template, "${match.reason}; Hermite S弯，最小半径≈%.2fm".format(Locale.US, minRadius))
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
        val tangentDir = Vector3d(tangent2d)
        if (tangentDir.lengthSquared() < 1e-12) return false
        tangentDir.normalize()
        return Vector3d(chord2d).normalize().dot(tangentDir) >= cos(Math.toRadians(1.0)) &&
            horizontalLateralOffset(chord, tangentDir) <= Curve.TOLERANCE
    }

    private fun horizontalLateralOffset(chord: Vector3dc, tangent2d: Vector3dc): Double {
        val chordHorizontal = Vector3d(chord.x(), 0.0, chord.z())
        val tangentHorizontal = Vector3d(tangent2d.x(), 0.0, tangent2d.z())
        if (tangentHorizontal.lengthSquared() < 1e-12) return Double.POSITIVE_INFINITY
        tangentHorizontal.normalize()
        val longitudinalDistance = chordHorizontal.dot(tangentHorizontal)
        return chordHorizontal.sub(tangentHorizontal.mul(longitudinalDistance)).length()
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
            val (_, baseNormal, _) = CurveFrames.computeFrame(tangent)
            val expected = CurveFrames.applyRollRadians(baseNormal, tangent, Math.toRadians(rollDegrees))
            val a = Vector3d(actual)
            if (a.lengthSquared() < 1e-12) return false
            return a.normalize().dot(expected) >= tangentToleranceDot
        }

        return normalMatches(start.normal, fromTangent, fromRollDegrees) &&
            normalMatches(end.normal, toTangent, toRollDegrees)
    }

    private fun createHorizontallyStraightCurve(
        from: Vector3dc,
        to: Vector3dc,
        fromTangent: Vector3dc,
        toTangent: Vector3dc,
        fromRollDegrees: Double = 0.0,
        toRollDegrees: Double = 0.0,
        step: Double = RailGeometryParameters().defaultStepMeters
    ): PrimeCurve {
        require(step.isFinite() && step > 0.0)
        val horizontalChord = Vector3d(to.x() - from.x(), 0.0, to.z() - from.z())
        val horizontalLength = horizontalChord.length()
        require(horizontalLength > 1.0e-12)
        val horizontalDirection = horizontalChord.div(horizontalLength)
        val profile = VerticalProfileSegment(
            length = horizontalLength,
            startElevation = from.y(),
            endElevation = to.y(),
            startGrade = CurveFrames.horizontalGrade(fromTangent),
            endGrade = CurveFrames.horizontalGrade(toTangent)
        )
        val sampleCount = maxOf(1, ceil(horizontalLength / step).toInt())
        val points = ArrayList<Curve.RawPoint>(sampleCount + 1)
        var previousPosition: Vector3d? = null
        var cumulativeArc = 0.0
        for (index in 0..sampleCount) {
            val ratio = index.toDouble() / sampleCount
            val station = horizontalLength * ratio
            val position = Vector3d(from)
                .fma(station, horizontalDirection)
                .also { it.y = profile.heightAt(station) }
            previousPosition?.let { cumulativeArc += it.distance(position) }
            val tangent = Vector3d(
                horizontalDirection.x(),
                profile.gradeAt(station),
                horizontalDirection.z()
            ).normalize()
            val roll = fromRollDegrees + (toRollDegrees - fromRollDegrees) * ratio
            val (_, baseNormal, _) = CurveFrames.computeFrame(tangent)
            val normal = CurveFrames.applyRollRadians(baseNormal, tangent, Math.toRadians(roll))
            points += Curve.RawPoint(position, tangent, normal, cumulativeArc)
            previousPosition = position
        }
        return PrimeCurve.fromPoints(points)
    }

}
