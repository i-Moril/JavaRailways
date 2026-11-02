package work.art1st.javarailways.simpleato.control

import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.physics.train.RailTraversalSegmentSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import java.util.UUID
import work.art1st.javarailways.core.physics.rail.RailSegment
import work.art1st.javarailways.core.api.util.annotation.Heavy
import java.util.WeakHashMap
import org.joml.Vector3d

/** Bounds the entire immutable oriented curve, including normalized tangent interpolation extrema. */
internal object RouteGradeBounds {
    private val cache = WeakHashMap<PrimeCurve, Double>()

    fun mostAdverseRouteGrade(segments: List<RailSegment>, currentGrade: Double = 0.0): Double =
        segments.fold(currentGrade) { grade, segment -> minOf(grade, minimumGrade(segment.railView.curve)) }

    /** Body traversal follows the physical tail-to-head operating direction, including back rails. */
    fun mostAdverseOccupiedGrade(train: Train): Double {
        val frame = train.publishedFrame ?: return Double.NaN
        if (frame.trainId != train.id || frame.derailed) return Double.NaN
        return mostAdverseOccupiedGrade(frame.bodyTraversal) { railId, facing ->
            train.world.getRail(railId)?.getView(facing)?.curve
        }
    }

    /** Missing occupied geometry is unavailable safety evidence, never an assumed flat track. */
    internal fun mostAdverseOccupiedGrade(body: List<RailTraversalSegmentSnapshot>,
        curveFor: (UUID, ViewDirection) -> PrimeCurve?): Double {
        if (body.isEmpty()) return Double.NaN
        var grade = 1.0
        body.forEach { segment ->
            val curve = curveFor(segment.railId, segment.facing) ?: return Double.NaN
            grade = minOf(grade, minimumGrade(curve))
        }
        return grade
    }

    fun minimumGrade(curve: PrimeCurve): Double = synchronized(cache) {
        cache.getOrPut(curve) { buildMinimumGrade(curve) }
    }

    /**
     * One immutable curve scan on cache miss, not one scan per control tick. PrimeCurve exposes
     * no public raw-point index; stream() is its only complete sample source. Route preparation
     * can prewarm through minimumGrade; a newly selected manual route lazily warms once.
     */
    @OptIn(Heavy::class)
    private fun buildMinimumGrade(curve: PrimeCurve): Double {
        var minimum = 1.0
        var previous: Vector3d? = null
        curve.stream().use { points ->
            points.forEachOrdered { point ->
                val current = Vector3d(point.tangent)
                require(current.isFinite && current.lengthSquared().isFinite() && current.lengthSquared() > 0.0) { "invalid route tangent" }
                // Exact endpoints retain their original tangent in Core; interiors normalize it.
                minimum = minOf(minimum, current.y().coerceIn(-1.0, 1.0), current.y() / current.length())
                previous?.let { a ->
                    val scale = maxOf(a.length(), current.length())
                    val scaledA = Vector3d(a).div(scale)
                    val b = Vector3d(current).div(scale).sub(scaledA)
                    val aa = scaledA.dot(scaledA)
                    val ab = scaledA.dot(b)
                    val bb = b.dot(b)
                    // d[(a.y+t*b.y)/|a+t*b|]/dt has a linear numerator.
                    val constant = b.y() * aa - scaledA.y() * ab
                    val slope = b.y() * ab - scaledA.y() * bb
                    if (slope != 0.0) {
                        val t = -constant / slope
                        if (t > 0.0 && t < 1.0) {
                            val interior = Vector3d(b).mul(t).add(scaledA)
                            minimum = minOf(minimum, interior.y() / interior.length())
                        }
                    }
                }
                previous = current
            }
        }
        check(previous != null) { "empty route curve" }
        // Round towards more adverse grade, guarding the extremum arithmetic.
        // A tangent with exact zero vertical component contributes an exact zero grade; rounding
        // that to -Double.MIN_VALUE would select a wholly different downhill lookup cell.
        return if (minimum == 0.0) 0.0 else Math.nextDown(minimum).coerceIn(-1.0, 1.0)
    }
}
