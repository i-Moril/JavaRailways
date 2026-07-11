package work.art1st.mineRails.basic.curve

import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.api.physics.curve.PrimeCurve
import work.art1st.mineRails.core.api.util.annotation.Heavy
import work.art1st.mineRails.core.util.ThreadGuard
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class RailCurveTemplateType(val displayName: String) {
    AUTO("自动"),
    STRAIGHT("直线"),
    FIVE_SEGMENT("五段式曲线"),
    S_CURVE("S弯"),
    TURNOUT("道岔")
}

enum class StraightMode {
    /** 强制使用两端点 chord 作为直线方向；会重设端点 yaw，仅作为手动兜底选项。 */
    USE_CHORD,
    /** 要求锚点切线已经与 chord 对齐，避免轨道端点切线突变。 */
    REQUIRE_TANGENT
}

data class RailCurveBuildOptions(
    val template: RailCurveTemplateType = RailCurveTemplateType.AUTO,
    val radius: Double = 0.0,
    val straightMode: StraightMode = StraightMode.REQUIRE_TANGENT,
    val superelevation: SuperelevationConfig? = null
)

data class CurveTemplateMatch(
    val template: RailCurveTemplateType,
    val reason: String
)

data class RailCurveBuildResult(
    val curve: PrimeCurve?,
    val error: String? = null,
    val selectedTemplate: RailCurveTemplateType? = null,
    val selectedReason: String? = null
) {
    val isSuccess: Boolean get() = curve != null

    companion object {
        fun success(
            curve: PrimeCurve,
            template: RailCurveTemplateType,
            reason: String? = null
        ): RailCurveBuildResult = RailCurveBuildResult(curve, selectedTemplate = template, selectedReason = reason)

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
    val selectedReason: String? = null
) {
    val isSuccess: Boolean get() = curves != null

    companion object {
        fun success(
            curves: List<PrimeCurve>,
            template: RailCurveTemplateType?,
            reason: String? = null
        ): RailParallelCurveBuildResult = RailParallelCurveBuildResult(curves, selectedTemplate = template, selectedReason = reason)

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
            RailCurveTemplateType.STRAIGHT -> buildStraight(prepared, options, match)
            RailCurveTemplateType.FIVE_SEGMENT -> buildFiveSegment(prepared, options, match)
            RailCurveTemplateType.S_CURVE -> buildSCurve(prepared, options, match)
            RailCurveTemplateType.TURNOUT -> buildFiveSegment(
                prepared,
                options.copy(superelevation = null),
                match
            )
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
                RailParallelCurveBuildResult.success(listOf(result.curve!!), result.selectedTemplate, result.selectedReason)
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
        val curves = offsets.mapIndexed { index, offset ->
            offsetCurve(centerCurve, offset, fromAnchors[index], matchedEnds[index], tolerance)
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
        return RailParallelCurveBuildResult.success(curves, centerResult.selectedTemplate, centerResult.selectedReason)
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
        val position = Vector3d()
        val tangent = Vector3d()
        anchors.forEach { anchor ->
            position.add(anchor.position)
            tangent.add(anchor.tangent)
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
        maxEndpointCorrection: Double
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
            val tangent = tangentAt(offsetPositions, index) ?: Vector3d(source.tangent).normalize()
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
                CurveTemplateMatch(RailCurveTemplateType.STRAIGHT, "两端切线同向且 chord 与切线共线")
            } else {
                CurveTemplateMatch(RailCurveTemplateType.S_CURVE, "两端切线同向但 chord 与切线不共线")
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
        val horizontalDistance = Vector3d(prepared.chord.x(), 0.0, prepared.chord.z()).length()
        if (horizontalDistance < 1e-6) {
            return RailCurveBuildResult.failure("端点距离过短", match.template, match.reason)
        }
        val direction = Vector3d(prepared.chord.x(), 0.0, prepared.chord.z()).normalize()
        val (fromTangent, toTangent) = when (options.straightMode) {
            StraightMode.USE_CHORD -> {
                val fromGrade = horizontalGrade(prepared.fromTan3d)
                val toGrade = horizontalGrade(prepared.toTan3d)
                Vector3d(direction.x(), fromGrade, direction.z()).normalize() to
                    Vector3d(direction.x(), toGrade, direction.z()).normalize()
            }
            StraightMode.REQUIRE_TANGENT -> {
                if (!isChordAlignedWithTangent(prepared.chord, prepared.fromTan2d) ||
                    !isChordAlignedWithTangent(prepared.chord, prepared.toTan2d)
                ) {
                    return RailCurveBuildResult.failure("直线模板要求锚点切线与 chord 对齐", match.template, match.reason)
                }
                prepared.fromTan3d to prepared.toTan3d
            }
        }
        val curve = createStraightCurve(
            prepared.fromPos,
            prepared.toPos,
            direction,
            horizontalDistance,
            fromTangent,
            toTangent,
            prepared.fromRoll,
            prepared.toRoll
        )
        if (!validateGeneratedCurve(curve, prepared.fromPos, prepared.toPos, fromTangent, toTangent, prepared.fromRoll, prepared.toRoll)) {
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
            ConstraintSolver.solveBest(prepared.fromPos, prepared.fromTan2d, prepared.toPos, prepared.toTan2d)
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
            0.2,
            prepared.fromRoll,
            prepared.toRoll,
            options.superelevation
        )
        val curve = PrimeCurve.fromPoints(curvePoints)
        if (!validateGeneratedCurve(curve, prepared.fromPos, prepared.toPos, prepared.fromTan3d, prepared.toTan3d, prepared.fromRoll, prepared.toRoll)) {
            return RailCurveBuildResult.failure("五段式模板生成后端点位置/切线/roll 校验失败", match.template, match.reason)
        }
        return RailCurveBuildResult.success(curve, match.template, match.reason)
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

        val handle0 = hermiteHandleLength(prepared.chord, prepared.fromTan2d, horizontalDistance)
        val handle1 = hermiteHandleLength(prepared.chord, prepared.toTan2d, horizontalDistance)
        val minRadius = estimateHermiteMinRadius(
            prepared.fromPos,
            prepared.toPos,
            prepared.fromTan2d,
            prepared.toTan2d,
            handle0,
            handle1
        )
        if (options.radius > 0.0 && minRadius < options.radius) {
            return RailCurveBuildResult.failure(
                "S弯最小半径不足: %.2fm < %.2fm，请拉远锚点或减小横向偏移".format(Locale.US, minRadius, options.radius),
                match.template,
                match.reason
            )
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
            horizontalDistance
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

    private fun hermiteHandleLength(chord: Vector3dc, tangent2d: Vector3dc, horizontalDistance: Double): Double {
        val projection = abs(chord.x() * tangent2d.x() + chord.z() * tangent2d.z())
        return projection.coerceAtLeast(horizontalDistance * 0.6).coerceAtMost(horizontalDistance * 1.5)
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
        horizontalDistance: Double
    ): PrimeCurve {
        val sampleCount = maxOf(24, kotlin.math.ceil(horizontalDistance / 0.4).toInt())
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
        fun rollAt(arc: Double): Double {
            val t = (arc / totalLength).coerceIn(0.0, 1.0)
            return fromRollDegrees + (toRollDegrees - fromRollDegrees) * t
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
            val normal = applyRollToNormal(baseNormal, tangent, rollAt(arc))
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

    private fun estimateHermiteMinRadius(
        from: Vector3dc,
        to: Vector3dc,
        fromTangent2d: Vector3dc,
        toTangent2d: Vector3dc,
        handle0: Double,
        handle1: Double
    ): Double {
        var maxCurvature = 0.0
        for (i in 0..100) {
            val u = i / 100.0
            val d1 = hermiteDerivative2d(from, to, fromTangent2d, toTangent2d, handle0, handle1, u)
            val d2 = hermiteSecondDerivative2d(from, to, fromTangent2d, toTangent2d, handle0, handle1, u)
            val speedSq = d1.x() * d1.x() + d1.z() * d1.z()
            if (speedSq < 1e-12) continue
            val curvature = abs(d1.x() * d2.z() - d1.z() * d2.x()) / (speedSq * sqrt(speedSq))
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
