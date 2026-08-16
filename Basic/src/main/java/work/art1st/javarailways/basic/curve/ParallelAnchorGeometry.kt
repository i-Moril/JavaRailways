package work.art1st.javarailways.basic.curve

import org.joml.Vector3d
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

sealed class ParallelAnchorMatch {
    data class Success(val start: List<Anchor>, val end: List<Anchor>) : ParallelAnchorMatch()
    data class Failure(val reason: String) : ParallelAnchorMatch()
}

/** Validates and orders parallel anchor cross sections without persisted lane correspondence. */
object ParallelAnchorGeometry {
    private const val MIN_VECTOR_SQUARED = 1.0e-12
    private val tangentToleranceDot = cos(Math.toRadians(1.0))

    fun match(
        start: List<Anchor>,
        end: List<Anchor>,
        expectedSpacing: Double
    ): ParallelAnchorMatch {
        if (start.isEmpty() || start.size != end.size) {
            return ParallelAnchorMatch.Failure("并行横断面锚点数量不一致")
        }
        if (!expectedSpacing.isFinite() || expectedSpacing <= 0.0) {
            return ParallelAnchorMatch.Failure("并行轨道间距无效")
        }
        if (start.size == 1) return ParallelAnchorMatch.Success(start, end)
        val worldIds = (start + end).map(Anchor::worldId).filterNotNull().distinct()
        if (worldIds.size > 1) return ParallelAnchorMatch.Failure("并行锚点不在同一世界")

        val startCenter = center(start)
        val endCenter = center(end)
        val travel = Vector3d(endCenter).sub(startCenter).also { it.y = 0.0 }
        if (travel.lengthSquared() < MIN_VECTOR_SQUARED) {
            return ParallelAnchorMatch.Failure("并行横断面中心重合")
        }
        travel.normalize()

        val orderedStart = when (val result = validateAndOrderCrossSection(start, travel, expectedSpacing, "起点")) {
            is CrossSectionResult.Success -> result.anchors
            is CrossSectionResult.Failure -> return ParallelAnchorMatch.Failure(result.reason)
        }
        val orderedEnd = when (val result = validateAndOrderCrossSection(end, travel, expectedSpacing, "终点")) {
            is CrossSectionResult.Success -> result.anchors
            is CrossSectionResult.Failure -> return ParallelAnchorMatch.Failure(result.reason)
        }
        return ParallelAnchorMatch.Success(orderedStart, orderedEnd)
    }

    private sealed class CrossSectionResult {
        data class Success(val anchors: List<Anchor>) : CrossSectionResult()
        data class Failure(val reason: String) : CrossSectionResult()
    }

    private fun validateAndOrderCrossSection(
        anchors: List<Anchor>,
        travel: Vector3d,
        expectedSpacing: Double,
        label: String
    ): CrossSectionResult {
        val reference = horizontalTangent(anchors.first())
            ?: return CrossSectionResult.Failure("$label 锚点切线无效")
        if (reference.dot(travel) < 0.0) reference.negate()
        val tangentSum = Vector3d()
        for (anchor in anchors) {
            val tangent = horizontalTangent(anchor)
                ?: return CrossSectionResult.Failure("$label 锚点切线无效")
            if (tangent.dot(reference) < 0.0) tangent.negate()
            if (tangent.dot(reference) < tangentToleranceDot) {
                return CrossSectionResult.Failure("$label 锚点切线不平行")
            }
            tangentSum.add(tangent)
        }
        if (tangentSum.lengthSquared() < MIN_VECTOR_SQUARED) {
            return CrossSectionResult.Failure("$label 公共切线无效")
        }
        val longitudinal = tangentSum.normalize()
        val right = Vector3d(longitudinal).cross(Vector3d(0.0, 1.0, 0.0)).normalize()
        val center = center(anchors)
        val sectionTolerance = max(0.25, expectedSpacing * 0.05)
        val elevationTolerance = max(0.05, expectedSpacing * 0.02)
        val projected = anchors.map { anchor ->
            val offset = Vector3d(anchor.position).sub(center)
            if (abs(offset.dot(longitudinal)) > sectionTolerance) {
                return CrossSectionResult.Failure("$label 锚点不在同一横断面")
            }
            if (abs(anchor.position.y() - center.y) > elevationTolerance) {
                return CrossSectionResult.Failure("$label 锚点高程不满足并行约束")
            }
            anchor to offset.dot(right)
        }.sortedBy { it.second }

        val spacingTolerance = max(0.25, expectedSpacing * 0.15)
        projected.zipWithNext().forEach { (left, rightAnchor) ->
            val spacing = rightAnchor.second - left.second
            if (abs(spacing - expectedSpacing) > spacingTolerance) {
                return CrossSectionResult.Failure(
                    "$label 锚点间距应为 ${expectedSpacing}m，当前 %.2fm".format(spacing)
                )
            }
        }
        return CrossSectionResult.Success(projected.map { it.first })
    }

    private fun horizontalTangent(anchor: Anchor): Vector3d? {
        val tangent = Vector3d(anchor.tangent.x(), 0.0, anchor.tangent.z())
        if (tangent.lengthSquared() < MIN_VECTOR_SQUARED) return null
        return tangent.normalize()
    }

    private fun center(anchors: List<Anchor>): Vector3d = Vector3d().also { center ->
        anchors.forEach { center.add(it.position) }
        center.div(anchors.size.toDouble())
    }

}
