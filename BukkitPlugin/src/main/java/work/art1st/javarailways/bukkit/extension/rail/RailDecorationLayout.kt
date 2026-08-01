package work.art1st.javarailways.bukkit.extension.rail

import kotlin.math.ceil

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
}
