package work.art1st.javarailways.basic.api.ato

import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.physics.train.RailTraversalSegmentSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import java.util.UUID
import work.art1st.javarailways.core.api.util.annotation.Heavy
import java.util.WeakHashMap
import org.joml.Vector3d

/**
 * First-party physical grade queries, without independent third-party API compatibility guarantees.
 * Bounds whole immutable oriented curves, including normalized tangent interpolation extrema;
 * it does not limit the bound to a route or occupied subinterval. Grade is dimensionless sin(theta),
 * positive uphill in the curve's facing. No operating policy, registration or persistence is owned here.
 */
object RailGradeBounds {
    private val cache = WeakHashMap<PrimeCurve, Double>()

    /**
     * Reads [train]'s published frame once at invocation, then resolves each body rail's current
     * oriented curve. Body traversal follows physical tail-to-head operating facing, including back
     * rails; each lookup contributes its entire curve, even if the occupied interval is shorter.
     * Call within the world's allowed simulation/physics read context. The caller owns lifecycle
     * and currentness checks; this query does not freeze live rail geometry with the published frame.
     *
     * @param train train whose published body and world geometry are queried.
     * @return most adverse occupied grade, or NaN for a missing frame, mismatched train ID,
     * derailed/empty body or missing occupied geometry. Invalid-curve failures propagate unchanged.
     */
    @JvmStatic
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

    /**
     * Bounds the full oriented [curve], including exact endpoint tangents and normalized interpolation
     * between samples. Nonzero bounds round downward and clamp to [-1, 1]; flat grade remains exact
     * positive zero. Pure geometry query, safe across threads through a synchronized weak-key cache.
     * A cache miss scans all samples once while holding that monitor; hits reuse the computed bound.
     *
     * @param curve immutable curve already oriented in the direction of travel.
     * @return conservative minimum dimensionless grade over the entire curve.
     * @throws IllegalArgumentException if a sampled tangent is non-finite or not normalizable.
     * @throws IllegalStateException if the curve has no sampled points.
     */
    @JvmStatic
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
