package work.art1st.javarailways.basic.curve

import org.joml.Vector3d
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.util.annotation.Heavy
import kotlin.math.abs

/** Applies a solved vertical profile to sampled horizontal curve geometry. */
object VerticalCurveRebuilder {
    private const val LENGTH_TOLERANCE = 1.0e-4

    @OptIn(Heavy::class)
    fun smooth(
        sourceCurves: List<PrimeCurve>,
        horizontalSegments: Set<Int> = sourceCurves.indices.filterTo(linkedSetOf()) {
            isHorizontal(sourceCurves[it])
        }
    ): List<PrimeCurve> {
        require(sourceCurves.isNotEmpty())
        val lengths = sourceCurves.map(::horizontalLength)
        val stations = mutableListOf(0.0)
        lengths.forEach { stations += stations.last() + it }
        val elevations = sourceCurves.map { it.getPointAt(0.0).position.y() }.toMutableList().also {
            it += sourceCurves.last().getPointAt(sourceCurves.last().totalArcLength).position.y()
        }
        val start = sourceCurves.first().getPointAt(0.0).tangent
        val end = sourceCurves.last().getPointAt(sourceCurves.last().totalArcLength).tangent
        val profile = VerticalProfileSmoother.solve(
            stations,
            elevations,
            horizontalGrade(start),
            horizontalGrade(end),
            horizontalSegments
        )
        return rebuild(sourceCurves, profile)
    }

    @OptIn(Heavy::class)
    fun reversedCopy(curve: PrimeCurve): PrimeCurve = PrimeCurve.fromPoints(
        curve.reversed.stream().map { point ->
            Curve.RawPoint(
                Vector3d(point.position),
                Vector3d(point.tangent),
                Vector3d(point.normal),
                point.arcLength
            )
        }.toList()
    )

    @OptIn(Heavy::class)
    fun horizontalLength(curve: PrimeCurve): Double {
        val points = curve.stream().toList()
        return points.zipWithNext().sumOf { (a, b) ->
            kotlin.math.hypot(b.position.x() - a.position.x(), b.position.z() - a.position.z())
        }
    }

    @OptIn(Heavy::class)
    fun isHorizontal(curve: PrimeCurve): Boolean {
        val points = curve.stream().toList()
        val elevation = points.first().position.y()
        return points.all { point ->
            abs(point.position.y() - elevation) <= Curve.TOLERANCE && abs(point.tangent.y()) <= 1.0e-6
        }
    }

    @OptIn(Heavy::class)
    fun rebuild(sourceCurves: List<PrimeCurve>, profile: VerticalProfileSolution): List<PrimeCurve> {
        require(sourceCurves.size == profile.segments.size) {
            "Curve count does not match vertical-profile segment count"
        }
        return sourceCurves.mapIndexed { segmentIndex, source ->
            rebuildSegment(source, profile.segments[segmentIndex])
        }
    }

    @OptIn(Heavy::class)
    private fun rebuildSegment(source: PrimeCurve, profile: VerticalProfileSegment): PrimeCurve {
        val sourcePoints = source.stream().toList()
        require(sourcePoints.size >= 2) { "A source curve needs at least two points" }
        val horizontalStations = MutableList(sourcePoints.size) { 0.0 }
        for (index in 1 until sourcePoints.size) {
            val previous = sourcePoints[index - 1].position
            val current = sourcePoints[index].position
            horizontalStations[index] = horizontalStations[index - 1] + kotlin.math.hypot(
                current.x() - previous.x(),
                current.z() - previous.z()
            )
        }
        val horizontalLength = horizontalStations.last()
        require(horizontalLength > LENGTH_TOLERANCE) { "A vertical profile cannot be applied to a vertical curve" }
        require(abs(horizontalLength - profile.length) <= maxOf(LENGTH_TOLERANCE, profile.length * 1.0e-6)) {
            "Horizontal curve length $horizontalLength does not match profile length ${profile.length}"
        }

        val positions = sourcePoints.mapIndexed { index, point ->
            Vector3d(point.position.x(), profile.heightAt(horizontalStations[index]), point.position.z())
        }
        val cumulativeArc = MutableList(positions.size) { 0.0 }
        for (index in 1 until positions.size) {
            cumulativeArc[index] = cumulativeArc[index - 1] + positions[index - 1].distance(positions[index])
        }

        val rebuilt = sourcePoints.mapIndexed { index, sourcePoint ->
            val horizontalDirection = horizontalDirection(sourcePoints, index)
            val grade = profile.gradeAt(horizontalStations[index])
            val tangent = Vector3d(horizontalDirection.x(), grade, horizontalDirection.z()).normalize()
            val rollDegrees = AnchorGeometry.rollDegreesFromNormal(sourcePoint.tangent, sourcePoint.normal)
            val (_, baseNormal, _) = ConstraintSolver.computeFrame(tangent)
            val normal = RailCurveFactory.applyRollToNormal(baseNormal, tangent, rollDegrees)
            Curve.RawPoint(positions[index], tangent, normal, cumulativeArc[index])
        }
        return PrimeCurve.fromPoints(rebuilt)
    }

    private fun horizontalGrade(tangent: org.joml.Vector3dc): Double {
        val horizontal = kotlin.math.hypot(tangent.x(), tangent.z())
        return if (horizontal > 1.0e-12) tangent.y() / horizontal else 0.0
    }

    private fun horizontalDirection(points: List<Curve.Point>, index: Int): Vector3d {
        val sourceTangent = points[index].tangent
        val fromTangent = Vector3d(sourceTangent.x(), 0.0, sourceTangent.z())
        if (fromTangent.lengthSquared() > 1.0e-12) return fromTangent.normalize()

        val previous = points[maxOf(0, index - 1)].position
        val next = points[minOf(points.lastIndex, index + 1)].position
        val fallback = Vector3d(next.x() - previous.x(), 0.0, next.z() - previous.z())
        require(fallback.lengthSquared() > 1.0e-12) { "Cannot recover horizontal curve tangent" }
        return fallback.normalize()
    }
}
