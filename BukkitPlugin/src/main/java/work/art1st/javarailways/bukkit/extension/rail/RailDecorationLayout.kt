package work.art1st.javarailways.bukkit.extension.rail

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/** Arc-length placement shared by persistence bootstrap, rendering, and wand previews. */
internal object RailDecorationLayout {
    private const val EPSILON = 1.0e-9

    /**
     * Subdivide the full curve so adjacent model origins are never farther apart than
     * [repeatLength], while representing both physical endpoints exactly.
     */
    fun endpointInclusiveArcs(totalArcLength: Double, repeatLength: Double): List<Double> {
        require(totalArcLength.isFinite() && totalArcLength >= 0.0)
        require(repeatLength.isFinite() && repeatLength > 0.0)
        if (totalArcLength <= EPSILON) return listOf(0.0)

        val intervalCount = ceil(totalArcLength / repeatLength).toInt().coerceAtLeast(1)
        val intervalLength = totalArcLength / intervalCount
        return List(intervalCount + 1) { index ->
            if (index == intervalCount) totalArcLength else intervalLength * index
        }
    }

    /** Assign a global layout to one render segment without duplicating shared boundaries. */
    fun arcsInSegment(
        arcs: List<Double>,
        arcStart: Double,
        arcEnd: Double,
        includeEnd: Boolean
    ): List<Double> = arcs.filter { arc ->
        arc + EPSILON >= arcStart &&
            if (includeEnd) arc <= arcEnd + EPSILON else arc < arcEnd - EPSILON
    }

    /**
     * Bound persisted render samples to the current curve segment. Curve and render metadata
     * are serialized independently, so their endpoint doubles can differ by a few ULPs.
     */
    fun sanitizeArcsInSegment(
        arcs: List<Double>,
        arcStart: Double,
        arcEnd: Double
    ): List<Double> {
        require(arcStart.isFinite() && arcEnd.isFinite() && arcStart <= arcEnd)
        val scale = max(abs(arcStart), abs(arcEnd))
        val tolerance = max(EPSILON, Math.ulp(scale) * 4.0)
        return arcs.asSequence()
            .filter(Double::isFinite)
            .filter { it >= arcStart - tolerance && it <= arcEnd + tolerance }
            .map { it.coerceIn(arcStart, arcEnd) }
            .distinct()
            .toList()
    }
}
