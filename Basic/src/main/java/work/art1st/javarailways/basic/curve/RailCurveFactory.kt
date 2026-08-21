package work.art1st.javarailways.basic.curve

import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.util.annotation.Heavy
import work.art1st.javarailways.core.util.ThreadGuard
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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

data class RailCurveBuildResult(
    val curve: PrimeCurve?,
    val error: String? = null,
    val selectedTemplate: RailCurveTemplateType? = null,
    val selectedReason: String? = null,
    /** Internal player-invisible boundaries as fractions of total arc length. */
    val logicalBreakFractions: List<Double> = emptyList()
) {
    val isSuccess: Boolean get() = curve != null

    companion object {
        fun success(
            curve: PrimeCurve,
            template: RailCurveTemplateType,
            reason: String? = null,
            logicalBreakFractions: List<Double> = emptyList()
        ): RailCurveBuildResult = RailCurveBuildResult(
            curve,
            selectedTemplate = template,
            selectedReason = reason,
            logicalBreakFractions = logicalBreakFractions
        )

        fun failure(
            error: String,
            template: RailCurveTemplateType? = null,
            reason: String? = null
        ): RailCurveBuildResult = RailCurveBuildResult(null, error, template, reason)
    }
}

data class RailParallelCurveBuildResult(
    val curves: List<PrimeCurve>?,
    val error: String? = null,
    val selectedTemplate: RailCurveTemplateType? = null,
    val selectedReason: String? = null,
    val logicalBreakFractions: List<Double> = emptyList()
) {
    val isSuccess: Boolean get() = curves != null

    companion object {
        fun success(
            curves: List<PrimeCurve>,
            template: RailCurveTemplateType?,
            reason: String? = null,
            logicalBreakFractions: List<Double> = emptyList()
        ): RailParallelCurveBuildResult = RailParallelCurveBuildResult(
            curves,
            selectedTemplate = template,
            selectedReason = reason,
            logicalBreakFractions = logicalBreakFractions
        )

        fun failure(
            error: String,
            template: RailCurveTemplateType? = null,
            reason: String? = null
        ): RailParallelCurveBuildResult = RailParallelCurveBuildResult(null, error, template, reason)
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
            val result = build(fromAnchors.single(), toAnchors.single(), options)
            return if (result.isSuccess) {
                RailParallelCurveBuildResult.success(
                    listOf(result.curve!!),
                    result.selectedTemplate,
                    result.selectedReason,
                    result.logicalBreakFractions
                )
            } else {
                RailParallelCurveBuildResult.failure(result.error ?: "曲线创建失败", result.selectedTemplate, result.selectedReason)
            }
        }

        val centerFrom = centerAnchor("parallel-center-from", fromAnchors)
            ?: return RailParallelCurveBuildResult.failure("无法计算并行起点中心")
        val centerTo = centerAnchor("parallel-center-to", toAnchors)
            ?: return RailParallelCurveBuildResult.failure("无法计算并行终点中心")
        val centerResult = build(centerFrom, centerTo, options)
        if (!centerResult.isSuccess) {
            return RailParallelCurveBuildResult.failure(centerResult.error ?: "中心曲线创建失败", centerResult.selectedTemplate, centerResult.selectedReason)
        }

        val centerCurve = centerResult.curve!!
        val startRight = rightVector(centerCurve.getPointAt(0.0).tangent)
            ?: return RailParallelCurveBuildResult.failure("无法计算并行起点偏移方向", centerResult.selectedTemplate, centerResult.selectedReason)
        val endRight = rightVector(centerCurve.getPointAt(centerCurve.totalArcLength).tangent)
            ?: return RailParallelCurveBuildResult.failure("无法计算并行终点偏移方向", centerResult.selectedTemplate, centerResult.selectedReason)
        val offsets = fromAnchors.map { anchor -> Vector3d(anchor.position).sub(centerFrom.position).dot(startRight) }
        val unmatchedEnds = toAnchors.map { anchor ->
            anchor to Vector3d(anchor.position).sub(centerTo.position).dot(endRight)
        }.toMutableList()
        val matchedEnds = offsets.map { offset ->
            val match = unmatchedEnds.minBy { (_, endOffset) -> abs(endOffset - offset) }
            unmatchedEnds.remove(match)
            match.first
        }
        val tolerance = maxOf(0.25, estimatedSpacing(offsets) * 0.1)
        val preserveAnchorEndpointTangents = centerResult.selectedTemplate != RailCurveTemplateType.STRAIGHT
        val curves = offsets.mapIndexed { index, offset ->
            offsetCurve(
                centerCurve,
                offset,
                fromAnchors[index],
                matchedEnds[index],
                tolerance,
                preserveAnchorEndpointTangents
            )
                ?: return RailParallelCurveBuildResult.failure(
                    "并行曲线端点偏离锚点过大: ${fromAnchors[index].id}→${matchedEnds[index].id}",
                    centerResult.selectedTemplate,
                    centerResult.selectedReason
                )
        }
        curves.forEachIndexed { index, curve ->
            val startDistance = curve.getPointAt(0.0).position.distance(fromAnchors[index].position)
            val endDistance = curve.getPointAt(curve.totalArcLength).position.distance(matchedEnds[index].position)
            if (startDistance > Curve.TOLERANCE || endDistance > Curve.TOLERANCE) {
                return RailParallelCurveBuildResult.failure(
                    "并行曲线端点未贴合锚点: ${fromAnchors[index].id}→${matchedEnds[index].id}",
                    centerResult.selectedTemplate,
                    centerResult.selectedReason
                )
            }
        }
        return RailParallelCurveBuildResult.success(
            curves,
            centerResult.selectedTemplate,
            centerResult.selectedReason,
            centerResult.logicalBreakFractions
        )
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

    private fun centerAnchor(id: String, anchors: List<Anchor>): Anchor? {
        if (anchors.isEmpty()) return null
        val referenceTangent = Vector3d(anchors.first().tangent)
        if (referenceTangent.lengthSquared() < 1e-12) return null
        referenceTangent.normalize()

        val position = Vector3d()
        val tangent = Vector3d()
        anchors.forEach { anchor ->
            position.add(anchor.position)
            val candidate = Vector3d(anchor.tangent)
            if (candidate.lengthSquared() < 1e-12) return null
            candidate.normalize()
            if (candidate.dot(referenceTangent) < 0.0) candidate.negate()
            tangent.add(candidate)
        }
        position.div(anchors.size.toDouble())
        if (tangent.lengthSquared() < 1e-12) return null
        tangent.normalize()
        return Anchor(id, position, tangent, anchors.firstOrNull()?.worldId)
    }

    private fun rightVector(tangent: Vector3dc): Vector3d? {
        val horizontal = Vector3d(tangent.x(), 0.0, tangent.z())
        if (horizontal.lengthSquared() < 1e-12) return null
        return horizontal.normalize().cross(Vector3d(0.0, 1.0, 0.0)).normalize()
    }

    private fun estimatedSpacing(offsets: List<Double>): Double {
        if (offsets.size < 2) return 0.0
        return offsets.sorted().zipWithNext { a, b -> abs(b - a) }.average()
    }

    @OptIn(Heavy::class)
    private fun offsetCurve(
        curve: PrimeCurve,
        offset: Double,
        fromAnchor: Anchor,
        toAnchor: Anchor,
        maxEndpointCorrection: Double,
        preserveAnchorEndpointTangents: Boolean
    ): PrimeCurve? {
        val centerPoints = curve.stream().toList()
        val rawOffsetPositions = centerPoints.map { point ->
            val right = rightVector(point.tangent) ?: Vector3d(1.0, 0.0, 0.0)
            Vector3d(point.position).fma(offset, right)
        }
        val startCorrection = Vector3d(fromAnchor.position).sub(rawOffsetPositions.first())
        val endCorrection = Vector3d(toAnchor.position).sub(rawOffsetPositions.last())
        if (startCorrection.length() > maxEndpointCorrection || endCorrection.length() > maxEndpointCorrection) {
            return null
        }
        val totalArc = curve.totalArcLength
        val offsetPositions = rawOffsetPositions.mapIndexed { index, position ->
            val ratio = if (totalArc > 1e-12) {
                (centerPoints[index].arcLength / totalArc).coerceIn(0.0, 1.0)
            } else if (rawOffsetPositions.size > 1) {
                index.toDouble() / (rawOffsetPositions.size - 1).toDouble()
            } else {
                0.0
            }
            Vector3d(position)
                .fma(1.0 - ratio, startCorrection)
                .fma(ratio, endCorrection)
        }
        val cumulative = MutableList(offsetPositions.size) { 0.0 }
        for (i in 1 until offsetPositions.size) {
            cumulative[i] = cumulative[i - 1] + offsetPositions[i - 1].distance(offsetPositions[i])
        }
        val raw = offsetPositions.mapIndexed { index, position ->
            val source = centerPoints[index]
            val tangent = if (preserveAnchorEndpointTangents) {
                when (index) {
                    0 -> endpointTangent(fromAnchor.tangent, source.tangent)
                    offsetPositions.lastIndex -> endpointTangent(toAnchor.tangent, source.tangent)
                    else -> tangentAt(offsetPositions, index) ?: Vector3d(source.tangent).normalize()
                }
            } else {
                tangentAt(offsetPositions, index) ?: Vector3d(source.tangent).normalize()
            }
            val sourceTangent = Vector3d(source.tangent)
            if (sourceTangent.lengthSquared() > 1e-12 && tangent.dot(sourceTangent.normalize()) < 0.0) {
                tangent.negate()
            }
            val normal = Vector3d(source.normal)
            val projection = normal.dot(tangent)
            normal.sub(Vector3d(tangent).mul(projection))
            if (normal.lengthSquared() < 1e-12) normal.set(0.0, 1.0, 0.0) else normal.normalize()
            Curve.RawPoint(position, tangent, normal, cumulative[index])
        }
        return PrimeCurve.fromPoints(raw)
    }

    private fun endpointTangent(anchorTangent: Vector3dc, curveTangent: Vector3dc): Vector3d {
        val tangent = Vector3d(anchorTangent).normalize()
        if (tangent.dot(curveTangent) < 0.0) tangent.negate()
        return tangent
    }

    private fun tangentAt(positions: List<Vector3d>, index: Int): Vector3d? {
        if (positions.size < 2) return null
        val previous = positions[maxOf(0, index - 1)]
        val next = positions[minOf(positions.lastIndex, index + 1)]
        val tangent = Vector3d(next).sub(previous)
        if (tangent.lengthSquared() < 1e-12) return null
        return tangent.normalize()
    }

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
        val breakFractions = listOf(
            result.straightA / result.totalLength,
            (result.totalLength - result.straightB) / result.totalLength
        )
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

        var handle0 = hermiteHandleLength(
            prepared.chord, prepared.fromTan2d, horizontalDistance, options.geometry
        )
        var handle1 = hermiteHandleLength(
            prepared.chord, prepared.toTan2d, horizontalDistance, options.geometry
        )
        var minRadius = estimateHermiteMinRadius(
            prepared.fromPos,
            prepared.toPos,
            prepared.fromTan2d,
            prepared.toTan2d,
            handle0,
            handle1,
            options.geometry.sCurveCurvatureProbeCount
        )
        if (options.radius > 0.0) {
            val fitted = fitHermiteHandlesToExactRadius(
                prepared,
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

        val curve = createHermiteSCurve(
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

    private fun horizontalGrade(tangent: Vector3dc): Double {
        val horizontal = sqrt(tangent.x() * tangent.x() + tangent.z() * tangent.z())
        return if (horizontal > 1e-10) tangent.y() / horizontal else 0.0
    }

    private fun hermiteHandleLength(
        chord: Vector3dc,
        tangent2d: Vector3dc,
        horizontalDistance: Double,
        geometry: RailGeometryParameters
    ): Double {
        val projection = abs(chord.x() * tangent2d.x() + chord.z() * tangent2d.z())
        return projection
            .coerceAtLeast(horizontalDistance * geometry.sCurveMinimumHandleFactor)
            .coerceAtMost(horizontalDistance * geometry.sCurveMaximumHandleFactor)
    }

    private fun createHermiteSCurve(
        from: Vector3dc,
        to: Vector3dc,
        fromTangent: Vector3dc,
        toTangent: Vector3dc,
        fromTangent2d: Vector3dc,
        toTangent2d: Vector3dc,
        handle0: Double,
        handle1: Double,
        fromRollDegrees: Double,
        toRollDegrees: Double,
        horizontalDistance: Double,
        superelevationConfig: SuperelevationConfig?,
        geometry: RailGeometryParameters
    ): PrimeCurve {
        val sampleCount = maxOf(
            geometry.sCurveMinimumSamples,
            kotlin.math.ceil(horizontalDistance / geometry.sCurveTargetStepMeters).toInt()
        )
        val horizontalSamples = (0..sampleCount).map { index ->
            val u = index.toDouble() / sampleCount
            val p = hermitePosition2d(from, to, fromTangent2d, toTangent2d, handle0, handle1, u)
            val d = hermiteDerivative2d(from, to, fromTangent2d, toTangent2d, handle0, handle1, u)
            p to d
        }
        val cumulative = MutableList(horizontalSamples.size) { 0.0 }
        for (i in 1 until horizontalSamples.size) {
            cumulative[i] = cumulative[i - 1] + horizontalSamples[i - 1].first.distance(horizontalSamples[i].first)
        }
        val totalLength = cumulative.last().coerceAtLeast(1e-9)
        val gA = horizontalGrade(fromTangent)
        val gB = horizontalGrade(toTangent)
        val dy = to.y() - from.y()
        val u = dy - gA * totalLength
        val v = gB - gA
        val c = (3.0 * u - v * totalLength) / (totalLength * totalLength)
        val d = (v * totalLength - 2.0 * u) / (totalLength * totalLength * totalLength)

        fun heightAt(arc: Double): Double = from.y() + gA * arc + c * arc * arc + d * arc * arc * arc
        fun gradeAt(arc: Double): Double = gA + 2.0 * c * arc + 3.0 * d * arc * arc
        val signedCurvatures = horizontalSamples.indices.map { index ->
            val parameter = index.toDouble() / sampleCount
            hermiteSignedCurvature(
                from,
                to,
                fromTangent2d,
                toTangent2d,
                handle0,
                handle1,
                parameter
            )
        }
        val peakCurvature = signedCurvatures.maxOf { abs(it) }
        val peakSuperelevation = if (superelevationConfig != null && peakCurvature > 1.0e-12) {
            superelevationConfig.calculateSuperelevation(1.0 / peakCurvature)
        } else {
            0.0
        }

        fun transitionFraction(value: Double): Double {
            val t = value.coerceIn(0.0, 1.0)
            return when (superelevationConfig?.superelevationTransition) {
                SuperelevationConfig.SuperelevationTransition.SINE_KLEIN ->
                    t - sin(2.0 * Math.PI * t) / (2.0 * Math.PI)
                else -> t
            }
        }

        // Cubic Hermite S-curves can have non-zero endpoint curvature. Keep automatic cant at
        // zero at both anchors, reverse it through the inflection, then scale the complete profile
        // so every sampled interval obeys the configured maximum cant gradient.
        val rawCantMeters = cumulative.indices.map { index ->
            if (peakSuperelevation <= 0.0 || peakCurvature <= 1.0e-12) return@map 0.0
            val t = (cumulative[index] / totalLength).coerceIn(0.0, 1.0)
            val endpointTransition = transitionFraction(2.0 * minOf(t, 1.0 - t))
            peakSuperelevation * (signedCurvatures[index] / peakCurvature) * endpointTransition
        }
        val rawPeakGradient = rawCantMeters.indices.drop(1).maxOfOrNull { index ->
            val deltaArc = cumulative[index] - cumulative[index - 1]
            if (deltaArc <= 1.0e-12) 0.0
            else abs(rawCantMeters[index] - rawCantMeters[index - 1]) / deltaArc
        } ?: 0.0
        val cantScale = when {
            superelevationConfig == null || rawPeakGradient <= 0.0 -> 1.0
            superelevationConfig.maxCantGradient <= 0.0 -> 0.0
            else -> minOf(1.0, superelevationConfig.maxCantGradient / rawPeakGradient)
        }

        fun rollAt(index: Int, arc: Double): Double {
            val t = (arc / totalLength).coerceIn(0.0, 1.0)
            val anchorRoll = fromRollDegrees + (toRollDegrees - fromRollDegrees) * t
            val cantDegrees = Math.toDegrees(
                kotlin.math.atan2(rawCantMeters[index] * cantScale, geometry.gaugeMeters)
            )
            return anchorRoll + cantDegrees
        }

        val points = horizontalSamples.mapIndexed { index, sample ->
            val arc = cumulative[index]
            val horizontalDerivative = sample.second
            val horizontalDirection = if (index == 0) {
                Vector3d(fromTangent2d)
            } else if (index == horizontalSamples.lastIndex) {
                Vector3d(toTangent2d)
            } else {
                Vector3d(horizontalDerivative.x(), 0.0, horizontalDerivative.z()).normalize()
            }
            val position = Vector3d(sample.first.x(), heightAt(arc), sample.first.z())
            if (index == horizontalSamples.lastIndex) position.set(to.x(), to.y(), to.z())
            if (index == 0) position.set(from.x(), from.y(), from.z())
            val tangent = Vector3d(horizontalDirection.x(), gradeAt(arc), horizontalDirection.z()).normalize()
            val (_, baseNormal, _) = ConstraintSolver.computeFrame(tangent)
            val normal = applyRollToNormal(baseNormal, tangent, rollAt(index, arc))
            Curve.RawPoint(position, tangent, normal, arc)
        }
        return PrimeCurve.fromPoints(points)
    }

    private fun hermitePosition2d(
        from: Vector3dc,
        to: Vector3dc,
        fromTangent2d: Vector3dc,
        toTangent2d: Vector3dc,
        handle0: Double,
        handle1: Double,
        u: Double
    ): Vector3d {
        val u2 = u * u
        val u3 = u2 * u
        val h00 = 2.0 * u3 - 3.0 * u2 + 1.0
        val h10 = u3 - 2.0 * u2 + u
        val h01 = -2.0 * u3 + 3.0 * u2
        val h11 = u3 - u2
        return Vector3d(
            h00 * from.x() + h10 * fromTangent2d.x() * handle0 + h01 * to.x() + h11 * toTangent2d.x() * handle1,
            0.0,
            h00 * from.z() + h10 * fromTangent2d.z() * handle0 + h01 * to.z() + h11 * toTangent2d.z() * handle1
        )
    }

    private fun hermiteDerivative2d(
        from: Vector3dc,
        to: Vector3dc,
        fromTangent2d: Vector3dc,
        toTangent2d: Vector3dc,
        handle0: Double,
        handle1: Double,
        u: Double
    ): Vector3d {
        val u2 = u * u
        val h00 = 6.0 * u2 - 6.0 * u
        val h10 = 3.0 * u2 - 4.0 * u + 1.0
        val h01 = -6.0 * u2 + 6.0 * u
        val h11 = 3.0 * u2 - 2.0 * u
        return Vector3d(
            h00 * from.x() + h10 * fromTangent2d.x() * handle0 + h01 * to.x() + h11 * toTangent2d.x() * handle1,
            0.0,
            h00 * from.z() + h10 * fromTangent2d.z() * handle0 + h01 * to.z() + h11 * toTangent2d.z() * handle1
        )
    }

    private fun hermiteSecondDerivative2d(
        from: Vector3dc,
        to: Vector3dc,
        fromTangent2d: Vector3dc,
        toTangent2d: Vector3dc,
        handle0: Double,
        handle1: Double,
        u: Double
    ): Vector3d {
        val h00 = 12.0 * u - 6.0
        val h10 = 6.0 * u - 4.0
        val h01 = -12.0 * u + 6.0
        val h11 = 6.0 * u - 2.0
        return Vector3d(
            h00 * from.x() + h10 * fromTangent2d.x() * handle0 + h01 * to.x() + h11 * toTangent2d.x() * handle1,
            0.0,
            h00 * from.z() + h10 * fromTangent2d.z() * handle0 + h01 * to.z() + h11 * toTangent2d.z() * handle1
        )
    }

    private fun hermiteSignedCurvature(
        from: Vector3dc,
        to: Vector3dc,
        fromTangent2d: Vector3dc,
        toTangent2d: Vector3dc,
        handle0: Double,
        handle1: Double,
        u: Double
    ): Double {
        val d1 = hermiteDerivative2d(from, to, fromTangent2d, toTangent2d, handle0, handle1, u)
        val d2 = hermiteSecondDerivative2d(from, to, fromTangent2d, toTangent2d, handle0, handle1, u)
        val speedSq = d1.x() * d1.x() + d1.z() * d1.z()
        if (speedSq < 1.0e-12) return 0.0
        return (d1.x() * d2.z() - d1.z() * d2.x()) / (speedSq * sqrt(speedSq))
    }

    private fun fitHermiteHandlesToExactRadius(
        prepared: PreparedAnchors,
        baseHandle0: Double,
        baseHandle1: Double,
        targetRadius: Double,
        probeCount: Int
    ): Triple<Double, Double, Double> {
        fun radiusAt(scale: Double): Double = estimateHermiteMinRadius(
            prepared.fromPos,
            prepared.toPos,
            prepared.fromTan2d,
            prepared.toTan2d,
            baseHandle0 * scale,
            baseHandle1 * scale,
            probeCount
        )

        val samples = (0..160).map { index ->
            val scale = 0.25 + (3.0 - 0.25) * index / 160.0
            scale to radiusAt(scale)
        }
        var best = samples.minBy { (_, radius) -> abs(radius - targetRadius) }
        samples.zipWithNext().forEach { (left, right) ->
            var lo = left.first
            var hi = right.first
            var loError = left.second - targetRadius
            val hiError = right.second - targetRadius
            if (!loError.isFinite() || !hiError.isFinite() || loError * hiError > 0.0) return@forEach
            repeat(48) {
                val mid = (lo + hi) / 2.0
                val radius = radiusAt(mid)
                if (abs(radius - targetRadius) < abs(best.second - targetRadius)) best = mid to radius
                val error = radius - targetRadius
                if (error == 0.0) return@repeat
                if (loError * error <= 0.0) {
                    hi = mid
                } else {
                    lo = mid
                    loError = error
                }
            }
        }
        return Triple(baseHandle0 * best.first, baseHandle1 * best.first, best.second)
    }

    private fun estimateHermiteMinRadius(
        from: Vector3dc,
        to: Vector3dc,
        fromTangent2d: Vector3dc,
        toTangent2d: Vector3dc,
        handle0: Double,
        handle1: Double,
        probeCount: Int
    ): Double {
        var maxCurvature = 0.0
        for (i in 0..probeCount) {
            val u = i.toDouble() / probeCount
            val curvature = abs(
                hermiteSignedCurvature(from, to, fromTangent2d, toTangent2d, handle0, handle1, u)
            )
            maxCurvature = maxOf(maxCurvature, curvature)
        }
        return if (maxCurvature < 1e-12) Double.POSITIVE_INFINITY else 1.0 / maxCurvature
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
            val (_, baseNormal, _) = ConstraintSolver.computeFrame(tangent)
            val expected = applyRollToNormal(baseNormal, tangent, rollDegrees)
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
            startGrade = horizontalGrade(fromTangent),
            endGrade = horizontalGrade(toTangent)
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
            val (_, baseNormal, _) = ConstraintSolver.computeFrame(tangent)
            val normal = applyRollToNormal(baseNormal, tangent, roll)
            points += Curve.RawPoint(position, tangent, normal, cumulativeArc)
            previousPosition = position
        }
        return PrimeCurve.fromPoints(points)
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
