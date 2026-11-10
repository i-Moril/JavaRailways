package work.art1st.javarailways.basic.curve

import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.util.annotation.Heavy

/** Authoring-only endpoint closure, before logical partitioning. Does not change existing rails. */
internal object CurveEndpointCorrection {
    const val MINIMUM_LENGTH_METERS = 1.0
    const val TRANSITION_LENGTH_METERS = 0.5
    const val MAXIMUM_CORRECTION_METERS = 0.005
    private const val TRANSITION_SUBDIVISIONS = 20

    data class Result(val curve: PrimeCurve, val logicalBreakFractions: List<Double>)

    /**
     * Retains source arc stations outside the endpoint transitions. The correspondence used for
     * engineering boundaries is explicit because transition refinement can insert samples.
     * Invalid length or an endpoint displacement over 5 mm throws before publishing any geometry.
     */
    @OptIn(Heavy::class)
    fun correct(
        source: PrimeCurve,
        from: Vector3dc,
        to: Vector3dc,
        logicalBreakFractions: List<Double> = emptyList()
    ): Result {
        require(logicalBreakFractions.all { it.isFinite() && it in 0.0..1.0 }) {
            "Curve break fractions must be finite and within [0, 1]"
        }
        val length = source.totalArcLength
        require(length >= MINIMUM_LENGTH_METERS) { "完整曲线长度不能短于 1 m" }
        val originalStart = source.getPointAt(0.0).position
        val originalEnd = source.getPointAt(length).position
        val start = Vector3d(from).sub(originalStart)
        val end = Vector3d(to).sub(originalEnd)
        fun withinLimit(displacement: Vector3d, original: Vector3dc, target: Vector3dc): Boolean {
            // A translated coordinate such as 10.005 cannot encode exactly the same 5 mm
            // subtraction as 0.005. Allow only coordinate representation roundoff at this gate.
            val x = maxOf(Math.ulp(original.x()), Math.ulp(target.x()))
            val y = maxOf(Math.ulp(original.y()), Math.ulp(target.y()))
            val z = maxOf(Math.ulp(original.z()), Math.ulp(target.z()))
            val roundoff = 2.0 * kotlin.math.sqrt(x * x + y * y + z * z)
            return displacement.isFinite && displacement.length() <= MAXIMUM_CORRECTION_METERS + roundoff
        }
        require(withinLimit(start, originalStart, from) && withinLimit(end, originalEnd, to)) {
            "曲线端点修正量不能超过 5 mm"
        }
        if (start.lengthSquared() == 0.0 && end.lengthSquared() == 0.0) {
            return Result(source, logicalBreakFractions.toList())
        }

        val stations = source.stream().map { it.arcLength }.toList().toMutableSet()
        // Even two-point/LINE inputs must have real samples at both transition boundaries.
        for (i in 0..TRANSITION_SUBDIVISIONS) {
            val distance = TRANSITION_LENGTH_METERS * i / TRANSITION_SUBDIVISIONS
            if (start.lengthSquared() != 0.0) stations += distance
            if (end.lengthSquared() != 0.0) stations += length - distance
        }
        // Source sampling may lie a few ulps from a mandated transition boundary. Keep the
        // exact boundary and merge that numerical duplicate before Core checks arc spacing.
        val boundaries = listOf(0.0, TRANSITION_LENGTH_METERS, length - TRANSITION_LENGTH_METERS, length)
        val sourceArcs = stations.map { station ->
            boundaries.firstOrNull { kotlin.math.abs(it - station) <= 1e-12 } ?: station
        }.sorted().fold(mutableListOf<Double>()) { merged, station ->
            if (merged.isEmpty() || station - merged.last() > 1e-12) merged += station
            merged
        }

        fun weight(u: Double): Double = u * u * u * (10.0 + u * (-15.0 + 6.0 * u))
        fun derivative(u: Double): Double = 30.0 * u * u * (1.0 - u) * (1.0 - u)
        fun displacement(s: Double): Vector3d {
            val a = (s / TRANSITION_LENGTH_METERS).coerceIn(0.0, 1.0)
            val b = ((length - s) / TRANSITION_LENGTH_METERS).coerceIn(0.0, 1.0)
            return Vector3d(start).mul(1.0 - weight(a)).fma(1.0 - weight(b), end)
        }
        fun velocity(s: Double): Vector3d {
            val a = (s / TRANSITION_LENGTH_METERS).coerceIn(0.0, 1.0)
            val b = ((length - s) / TRANSITION_LENGTH_METERS).coerceIn(0.0, 1.0)
            return Vector3d(source.getPointAt(s).tangent).normalize()
                .fma(-derivative(a) / TRANSITION_LENGTH_METERS, start)
                .fma(derivative(b) / TRANSITION_LENGTH_METERS, end)
        }
        var arc = 0.0
        val points = sourceArcs.mapIndexed { index, s ->
            if (index > 0) {
                val previous = sourceArcs[index - 1]
                val ds = s - previous
                // Integrate the correction against the source arc metric, preserving analytic
                // circle lengths exactly in every interval with no correction.
                arc += ds / 6.0 * (velocity(previous).length() +
                    4.0 * velocity((previous + s) / 2.0).length() + velocity(s).length())
            }
            val original = source.getPointAt(s)
            val position = when (index) {
                0 -> Vector3d(from)
                sourceArcs.lastIndex -> Vector3d(to)
                else -> Vector3d(original.position).add(displacement(s))
            }
            val tangent = velocity(s).normalize()
            val roll = AnchorGeometry.rollDegreesFromNormal(original.tangent, original.normal)
            val (_, baseNormal, _) = CurveFrames.computeFrame(tangent)
            val normal = CurveFrames.applyRollRadians(baseNormal, tangent, Math.toRadians(roll))
            Curve.RawPoint(position, tangent, normal, arc)
        }
        val corrected = PrimeCurve.fromPoints(points)
        require(corrected.totalArcLength >= MINIMUM_LENGTH_METERS) { "修正后的完整曲线长度不能短于 1 m" }
        val fractions = if (logicalBreakFractions.isEmpty()) emptyList() else {
            RailCurvePartitioner.mapStations(sourceArcs, points.map { it.arcLength },
                logicalBreakFractions.map { it * length }).map { it / corrected.totalArcLength }
        }
        return Result(corrected, fractions)
    }
}
