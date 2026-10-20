package work.art1st.javarailways.basic.curve

import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.util.annotation.Heavy
import kotlin.math.abs

/** Matches parallel endpoints and offsets an already built center curve. */
internal object ParallelCurveBuilder {
    fun build(
        fromAnchors: List<Anchor>,
        toAnchors: List<Anchor>,
        centerFrom: Anchor,
        centerTo: Anchor,
        centerResult: RailCurveBuildResult.Success
    ): RailParallelCurveBuildResult {
        val centerCurve = centerResult.curve
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
            curves.map { RailCurvePartitioner.remapBreakFractions(centerCurve, it, centerResult.logicalBreakFractions) }
        )
    }

    fun centerAnchor(id: String, anchors: List<Anchor>): Anchor? {
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

}
