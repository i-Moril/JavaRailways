package work.art1st.javarailways.tunnelbridgebuilder.algorithm

import kotlin.math.abs

/** Piecewise-linear center line whose samples also carry the section orientation. */
class SampledCenterLine(frames: List<SweepFrame>) {
    val frames: List<SweepFrame> = frames.toList()

    init {
        require(this.frames.size >= 2) { "Center line requires at least two frames" }
        this.frames.zipWithNext().forEachIndexed { index, (start, end) ->
            val delta = end.position - start.position
            require(delta.lengthSquared() > EPSILON_SQUARED) {
                "Center-line frames $index and ${index + 1} occupy the same position"
            }
            require((start.tangent dot delta) > 0.0 && (end.tangent dot delta) > 0.0) {
                "Center-line frame tangents must face in path order"
            }
        }
    }

    val start: SweepFrame get() = frames.first()
    val end: SweepFrame get() = frames.last()

    internal fun nearest(point: Vec3): NearestCenterLinePoint {
        var best: NearestCenterLinePoint? = null
        for (index in 0 until frames.lastIndex) {
            val start = frames[index]
            val end = frames[index + 1]
            val segment = end.position - start.position
            val segmentLengthSquared = segment.lengthSquared()
            val alpha = (((point - start.position) dot segment) / segmentLengthSquared)
                .coerceIn(0.0, 1.0)
            val position = start.position + segment * alpha
            val tangent = interpolateDirection(start.tangent, end.tangent, alpha)
            val normal = interpolateDirection(start.normal, end.normal, alpha)
            val frame = SweepFrame(position, tangent, normal)
            val distanceSquared = (point - position).lengthSquared()
            if (best == null || distanceSquared < best.distanceSquared) {
                best = NearestCenterLinePoint(frame, distanceSquared)
            }
        }
        return checkNotNull(best)
    }

    private fun interpolateDirection(start: Vec3, end: Vec3, alpha: Double): Vec3 {
        val blended = start * (1.0 - alpha) + end * alpha
        if (blended.lengthSquared() > EPSILON_SQUARED) return blended.normalized()
        // Opposed directions are rejected by path-order validation in normal input. Keep this
        // deterministic for near-degenerate floating-point frames.
        return if (abs(alpha) < 0.5) start else end
    }

    private companion object {
        const val EPSILON_SQUARED = 1.0e-18
    }
}
