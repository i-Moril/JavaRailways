package work.art1st.javarailways.basic.curve

import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.basic.rail.RailAssemblyRole
import work.art1st.javarailways.core.api.util.annotation.Heavy

/** One CoreRail-sized part of a player-facing curve. */
data class LogicalRailCurvePart(
    val curve: PrimeCurve,
    val role: RailAssemblyRole
)

object RailCurvePartitioner {
    /**
     * [curveRegionFractions] contains the start and end of the clothoid/arc/clothoid region.
     * Endpoint-valued boundaries mean the corresponding tangent section is absent.
     */
    @OptIn(Heavy::class)
    fun partitionFiveSegment(
        curve: PrimeCurve,
        curveRegionFractions: List<Double>
    ): List<LogicalRailCurvePart> {
        require(curveRegionFractions.size == 2) { "Five-segment partition needs curve start and end" }
        val start = curveRegionFractions[0].coerceIn(0.0, 1.0)
        val end = curveRegionFractions[1].coerceIn(0.0, 1.0)
        require(start.isFinite() && end.isFinite() && start < end)

        val totalLength = curve.totalArcLength
        val startArc = start * totalLength
        val endArc = end * totalLength
        val curveLength = endArc - startArc
        var keepLeading = startArc > Curve.TOLERANCE
        var keepTrailing = totalLength - endArc > Curve.TOLERANCE

        // A CoreRail shorter than the endpoint matching tolerance makes both of its ends look
        // like separate rails at the same node. Merge such a region into the curve part instead.
        if (keepLeading && keepTrailing && curveLength <= Curve.TOLERANCE) {
            if (startArc >= totalLength - endArc) keepTrailing = false else keepLeading = false
        }
        if (keepLeading && totalLength - startArc <= Curve.TOLERANCE) keepLeading = false
        if (keepTrailing && endArc <= Curve.TOLERANCE) keepTrailing = false

        val plannedRoles = buildList {
            if (keepLeading) add(RailAssemblyRole.LEADING_TANGENT)
            add(RailAssemblyRole.CURVE)
            if (keepTrailing) add(RailAssemblyRole.TRAILING_TANGENT)
        }
        val boundaries = buildList {
            if (keepLeading) add(startArc)
            if (keepTrailing) add(endArc)
        }

        val curves = mutableListOf<PrimeCurve>()
        var remainder = curve
        var consumed = 0.0
        boundaries.forEach { boundary ->
            val (before, after) = remainder.splitAt(boundary - consumed)
            curves += before
            remainder = after
            consumed = boundary
        }
        curves += remainder
        require(curves.size == plannedRoles.size)
        return curves.zip(plannedRoles) { part, role -> LogicalRailCurvePart(part, role) }
    }
}
