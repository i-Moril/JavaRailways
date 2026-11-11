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
        },
        fixedGrades: Map<Int, Double> = emptyMap()
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
            horizontalSegments,
            fixedGrades
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
        return RailCurvePartitioner.horizontalStations(points).last()
    }

    @OptIn(Heavy::class)
    fun isHorizontal(curve: PrimeCurve): Boolean {
        val points = curve.stream().toList()
        val elevation = points.first().position.y()
        return points.all { point ->
            abs(point.position.y() - elevation) <= Curve.TOLERANCE && abs(point.tangent.y()) <= 1.0e-6
        }
    }

    /** A chain is one authored curve; its internal/partition pieces may be shorter than 1 m. */
    @OptIn(Heavy::class)
    fun rebuild(sourceCurves: List<PrimeCurve>, profile: VerticalProfileSolution): List<PrimeCurve> {
        require(sourceCurves.size == profile.segments.size) {
            "Curve count does not match vertical-profile segment count"
        }
        require(sourceCurves.sumOf { it.totalArcLength } >= CurveEndpointCorrection.MINIMUM_LENGTH_METERS) {
            "完整曲线长度不能短于 1 m"
        }
        val rebuilt = sourceCurves.mapIndexed { segmentIndex, source ->
            rebuildSegment(source, profile.segments[segmentIndex])
        }
        require(rebuilt.sumOf { it.totalArcLength } >= CurveEndpointCorrection.MINIMUM_LENGTH_METERS) {
            "重建后的完整曲线长度不能短于 1 m"
        }
        return rebuilt
    }

    @OptIn(Heavy::class)
    private fun rebuildSegment(source: PrimeCurve, profile: VerticalProfileSegment): PrimeCurve {
        val sourcePoints = source.stream().toList()
        require(sourcePoints.size >= 2) { "A source curve needs at least two points" }
        val horizontalStations = RailCurvePartitioner.horizontalStations(sourcePoints)
        val horizontalLength = horizontalStations.last()
        require(horizontalLength > LENGTH_TOLERANCE) { "A vertical profile cannot be applied to a vertical curve" }
        require(abs(horizontalLength - profile.length) <= maxOf(LENGTH_TOLERANCE, profile.length * 1.0e-6)) {
            "Horizontal curve length $horizontalLength does not match profile length ${profile.length}"
        }

        val positions = sourcePoints.mapIndexed { index, point ->
            Vector3d(point.position.x(), when (index) {
                0 -> profile.startElevation
                sourcePoints.lastIndex -> profile.endElevation
                else -> profile.heightAt(horizontalStations[index])
            }, point.position.z())
        }
        val rebuilt = sourcePoints.mapIndexed { index, sourcePoint ->
            val horizontalDirection = horizontalDirection(sourcePoints, index)
            val grade = profile.gradeAt(horizontalStations[index])
            val tangent = Vector3d(horizontalDirection.x(), grade, horizontalDirection.z()).normalize()
            val rollDegrees = AnchorGeometry.rollDegreesFromNormal(sourcePoint.tangent, sourcePoint.normal)
            val (_, baseNormal, _) = CurveFrames.computeFrame(tangent)
            val normal = CurveFrames.applyRollRadians(baseNormal, tangent, Math.toRadians(rollDegrees))
            Curve.RawPoint(positions[index], tangent, normal, horizontalStations[index])
        }
        return PrimeCurve.fromPoints(RailCurvePartitioner.withElevationArc(rebuilt))
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
