package work.art1st.javarailways.basic.curve

import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.basic.rail.RailAssemblyRole
import work.art1st.javarailways.core.api.util.annotation.Heavy
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/** One CoreRail-sized part of a player-facing curve. */
data class LogicalRailCurvePart(
    val curve: PrimeCurve,
    val role: RailAssemblyRole
)

object RailCurvePartitioner {
    /** Source arcs are horizontal stations; elevation changes the emitted physical arc only. */
    internal fun withElevationArc(points: List<Curve.RawPoint>): List<Curve.RawPoint> {
        var arc = 0.0
        return points.mapIndexed { index, point ->
            if (index > 0) arc += hypot(point.arcLength - points[index - 1].arcLength,
                point.position.y() - points[index - 1].position.y())
            point.copy(arcLength = arc)
        }
    }

    /** Project each sampled physical segment, retaining its arc metric rather than shortening curved chords.
     * Height is linear between samples. Only floating-point roundoff may make ds slightly smaller than |dy|.
     */
    internal fun horizontalStations(points: List<Curve.Point>): List<Double> {
        var station = 0.0
        return points.mapIndexed { index, point ->
            if (index > 0) {
                val ds = point.arcLength - points[index - 1].arcLength
                val dy = point.position.y() - points[index - 1].position.y()
                require(ds > 0.0 && abs(dy) <= ds + maxOf(1e-10, ds * 1e-10)) {
                    "Curve segment height exceeds its physical arc length"
                }
                station += sqrt(((ds - abs(dy)) * (ds + abs(dy))).coerceAtLeast(0.0))
            }
            station
        }
    }

    /** Map coordinates by corresponding samples, interpolating only inside the enclosing sample interval. */
    internal fun mapStations(source: List<Double>, target: List<Double>, stations: List<Double>): List<Double> {
        require(source.size == target.size && source.size >= 2) { "Curve station samples must correspond" }
        require(source.first() == 0.0 && target.first() == 0.0 &&
            source.all { it.isFinite() } && target.all { it.isFinite() } &&
            source.zipWithNext().all { (a, b) -> b > a } && target.zipWithNext().all { (a, b) -> b > a }) {
            "Curve station samples must be finite and strictly increasing from zero"
        }
        return stations.map { station ->
            require(station.isFinite() && station >= 0.0 && station <= source.last() + 1e-8) { "Curve station is outside its source" }
            val clamped = station.coerceAtMost(source.last())
            val exact = source.binarySearch(clamped)
            if (exact >= 0) target[exact] else {
                val upper = -exact - 1
                val lower = upper - 1
                target[lower] + (target[upper] - target[lower]) *
                    ((clamped - source[lower]) / (source[upper] - source[lower]))
            }
        }
    }

    /** Offset/rebuild producers preserve sample order and count; these are not spatial nearest-point matches. */
    @OptIn(Heavy::class)
    fun remapBreakFractions(source: PrimeCurve, target: PrimeCurve, fractions: List<Double>): List<Double> {
        if (fractions.isEmpty()) return emptyList()
        return mapStations(source.stream().map { it.arcLength }.toList(),
            target.stream().map { it.arcLength }.toList(), fractions.map { it * source.totalArcLength })
            .map { it / target.totalArcLength }
    }

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
