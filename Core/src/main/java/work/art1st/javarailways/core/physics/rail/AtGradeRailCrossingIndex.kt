package work.art1st.javarailways.core.physics.rail

import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.javarailways.core.api.physics.rail.topology.AtGradeRailCrossing
import work.art1st.javarailways.core.api.physics.rail.topology.RailCrossingPoint
import java.util.UUID
import kotlin.math.abs

/** Maximum three-dimensional separation still treated as one approximate at-grade crossing. */
internal const val AT_GRADE_CROSSING_TOLERANCE_METERS = 0.2

/** Incremental structural-edit index of geometry crossings; it is never queried from the tick path. */
internal class AtGradeRailCrossingIndex(
    private val spatialIndex: RailSpatialIndex
) {
    private val crossingsByPair = linkedMapOf<RailPair, AtGradeRailCrossing>()
    private val sampledRails = hashMapOf<UUID, SampledRail>()

    fun addOrReplace(rail: CoreRail) {
        remove(rail.id)
        val addedGeometry = sample(rail)
        spatialIndex.addRail(rail, addedGeometry.samples.map(Sample::position))
        sampledRails[rail.id] = addedGeometry
        spatialIndex.candidatesFor(rail)
            .sortedBy { it.id.toString() }
            .forEach { candidate ->
                val candidateGeometry = checkNotNull(sampledRails[candidate.id]) {
                    "spatial candidate ${candidate.id} has no sampled crossing geometry"
                }
                val pair = RailPair.of(rail.id, candidate.id)
                val orderedFirst = if (pair.first == rail.id) rail else candidate
                val orderedSecond = if (orderedFirst === rail) candidate else rail
                val firstGeometry = if (orderedFirst === rail) addedGeometry else candidateGeometry
                val secondGeometry = if (orderedSecond === rail) addedGeometry else candidateGeometry
                if (!firstGeometry.bounds.overlaps(
                        secondGeometry.bounds,
                        AT_GRADE_CROSSING_TOLERANCE_METERS
                    )
                ) return@forEach
                val crossing = detect(
                    orderedFirst,
                    firstGeometry.samples,
                    orderedSecond,
                    secondGeometry.samples
                )
                if (crossing != null) crossingsByPair[pair] = crossing
            }
    }

    fun remove(railId: UUID) {
        crossingsByPair.keys.removeIf { it.first == railId || it.second == railId }
        sampledRails.remove(railId)
    }

    fun snapshot(): List<AtGradeRailCrossing> = crossingsByPair.entries
        .sortedWith(compareBy({ it.key.first.toString() }, { it.key.second.toString() }))
        .map { it.value }

    private fun detect(
        firstRail: CoreRail,
        firstSamples: List<Sample>,
        secondRail: CoreRail,
        secondSamples: List<Sample>
    ): AtGradeRailCrossing? {
        var bestDistanceSquared = Double.POSITIVE_INFINITY
        var bestCrossing: AtGradeRailCrossing? = null
        var bestApproachIsParallel = false
        for (firstIndex in 0 until firstSamples.lastIndex) {
            val firstStart = firstSamples[firstIndex]
            val firstEnd = firstSamples[firstIndex + 1]
            for (secondIndex in 0 until secondSamples.lastIndex) {
                val secondStart = secondSamples[secondIndex]
                val secondEnd = secondSamples[secondIndex + 1]
                if (!segmentBoundsOverlap(firstStart.position, firstEnd.position,
                        secondStart.position, secondEnd.position, AT_GRADE_CROSSING_TOLERANCE_METERS)
                ) continue
                val closest = closestSegmentApproach(
                    firstStart.position,
                    firstEnd.position,
                    secondStart.position,
                    secondEnd.position
                ) ?: continue
                if (closest.distanceSquared >
                    AT_GRADE_CROSSING_TOLERANCE_METERS * AT_GRADE_CROSSING_TOLERANCE_METERS
                ) continue
                if (closest.distanceSquared >= bestDistanceSquared) continue
                val firstArc = interpolate(firstStart.arcLength, firstEnd.arcLength, closest.firstFraction)
                val secondArc = interpolate(secondStart.arcLength, secondEnd.arcLength, closest.secondFraction)
                bestDistanceSquared = closest.distanceSquared
                bestApproachIsParallel = closest.parallel
                bestCrossing = AtGradeRailCrossing(
                    RailCrossingPoint(firstRail.id, firstArc),
                    RailCrossingPoint(secondRail.id, secondArc)
                )
            }
        }
        return bestCrossing?.takeUnless { bestApproachIsParallel }
    }

    private fun sample(rail: CoreRail): SampledRail {
        val samples = simplifyCollinear(
            rail.geometryBreakpoints().map { point -> Sample(Vector3d(point.position), point.arcLength) }
        )
        return SampledRail(samples, Bounds.of(samples))
    }

    /** Curve geometry is piecewise linear, so removing an interior collinear sample is exact. */
    private fun simplifyCollinear(samples: List<Sample>): List<Sample> {
        if (samples.size <= 2) return samples
        val simplified = ArrayList<Sample>(samples.size)
        simplified += samples.first()
        for (index in 1 until samples.lastIndex) {
            val previous = simplified.last().position
            val current = samples[index].position
            val next = samples[index + 1].position
            val incoming = Vector3d(current).sub(previous)
            val outgoing = Vector3d(next).sub(current)
            val scale = incoming.lengthSquared() * outgoing.lengthSquared()
            val collinear = scale > SEGMENT_EPSILON && incoming.dot(outgoing) >= 0.0 &&
                incoming.cross(outgoing, Vector3d()).lengthSquared() <= PARALLEL_EPSILON * scale
            if (!collinear) simplified += samples[index]
        }
        simplified += samples.last()
        return simplified
    }

    private fun segmentBoundsOverlap(
        firstStart: Vector3dc,
        firstEnd: Vector3dc,
        secondStart: Vector3dc,
        secondEnd: Vector3dc,
        tolerance: Double
    ): Boolean = axisOverlaps(firstStart.x(), firstEnd.x(), secondStart.x(), secondEnd.x(), tolerance) &&
        axisOverlaps(firstStart.y(), firstEnd.y(), secondStart.y(), secondEnd.y(), tolerance) &&
        axisOverlaps(firstStart.z(), firstEnd.z(), secondStart.z(), secondEnd.z(), tolerance)

    private fun axisOverlaps(
        firstStart: Double,
        firstEnd: Double,
        secondStart: Double,
        secondEnd: Double,
        tolerance: Double
    ): Boolean = minOf(firstStart, firstEnd) <= maxOf(secondStart, secondEnd) + tolerance &&
        minOf(secondStart, secondEnd) <= maxOf(firstStart, firstEnd) + tolerance

    /** Standard clamped closest-points calculation over both parallel and non-parallel segments. */
    private fun closestSegmentApproach(
        firstStart: Vector3dc,
        firstEnd: Vector3dc,
        secondStart: Vector3dc,
        secondEnd: Vector3dc
    ): SegmentApproach? {
        val firstDirection = Vector3d(firstEnd).sub(firstStart)
        val secondDirection = Vector3d(secondEnd).sub(secondStart)
        val firstLengthSquared = firstDirection.lengthSquared()
        val secondLengthSquared = secondDirection.lengthSquared()
        if (firstLengthSquared <= SEGMENT_EPSILON || secondLengthSquared <= SEGMENT_EPSILON) return null

        val crossLengthSquared = firstDirection.cross(secondDirection, Vector3d()).lengthSquared()
        if (crossLengthSquared <= PARALLEL_EPSILON * firstLengthSquared * secondLengthSquared) {
            return closestParallelApproach(
                firstStart,
                firstDirection,
                firstLengthSquared,
                secondStart,
                secondDirection,
                secondLengthSquared
            )
        }

        val betweenStarts = Vector3d(firstStart).sub(secondStart)
        val dotDirections = firstDirection.dot(secondDirection)
        val firstWithOffset = firstDirection.dot(betweenStarts)
        val secondWithOffset = secondDirection.dot(betweenStarts)
        val denominator = firstLengthSquared * secondLengthSquared - dotDirections * dotDirections

        var firstNumerator = dotDirections * secondWithOffset - secondLengthSquared * firstWithOffset
        var firstDenominator = denominator
        var secondNumerator = firstLengthSquared * secondWithOffset - dotDirections * firstWithOffset
        var secondDenominator = denominator

        if (firstNumerator < 0.0) {
            firstNumerator = 0.0
            secondNumerator = secondWithOffset
            secondDenominator = secondLengthSquared
        } else if (firstNumerator > firstDenominator) {
            firstNumerator = firstDenominator
            secondNumerator = secondWithOffset + dotDirections
            secondDenominator = secondLengthSquared
        }
        if (secondNumerator < 0.0) {
            secondNumerator = 0.0
            firstNumerator = (-firstWithOffset).coerceIn(0.0, firstLengthSquared)
            firstDenominator = firstLengthSquared
        } else if (secondNumerator > secondDenominator) {
            secondNumerator = secondDenominator
            firstNumerator = (dotDirections - firstWithOffset).coerceIn(0.0, firstLengthSquared)
            firstDenominator = firstLengthSquared
        }

        val firstFraction = if (abs(firstNumerator) <= SEGMENT_EPSILON) 0.0
            else firstNumerator / firstDenominator
        val secondFraction = if (abs(secondNumerator) <= SEGMENT_EPSILON) 0.0
            else secondNumerator / secondDenominator
        val separation = Vector3d(betweenStarts)
            .fma(firstFraction, firstDirection)
            .fma(-secondFraction, secondDirection)
        return SegmentApproach(firstFraction, secondFraction, separation.lengthSquared(), parallel = false)
    }

    private fun closestParallelApproach(
        firstStart: Vector3dc,
        firstDirection: Vector3dc,
        firstLengthSquared: Double,
        secondStart: Vector3dc,
        secondDirection: Vector3dc,
        secondLengthSquared: Double
    ): SegmentApproach = listOf(
        approach(
            firstStart,
            firstDirection,
            secondStart,
            secondDirection,
            firstFraction = 0.0,
            secondFraction = projectFraction(firstStart, secondStart, secondDirection, secondLengthSquared)
        ),
        approach(
            firstStart,
            firstDirection,
            secondStart,
            secondDirection,
            firstFraction = 1.0,
            secondFraction = projectFraction(
                Vector3d(firstStart).add(firstDirection),
                secondStart,
                secondDirection,
                secondLengthSquared
            )
        ),
        approach(
            firstStart,
            firstDirection,
            secondStart,
            secondDirection,
            firstFraction = projectFraction(secondStart, firstStart, firstDirection, firstLengthSquared),
            secondFraction = 0.0
        ),
        approach(
            firstStart,
            firstDirection,
            secondStart,
            secondDirection,
            firstFraction = projectFraction(
                Vector3d(secondStart).add(secondDirection),
                firstStart,
                firstDirection,
                firstLengthSquared
            ),
            secondFraction = 1.0
        )
    ).minBy(SegmentApproach::distanceSquared).copy(parallel = true)

    private fun projectFraction(
        point: Vector3dc,
        segmentStart: Vector3dc,
        segmentDirection: Vector3dc,
        segmentLengthSquared: Double
    ): Double = Vector3d(point).sub(segmentStart).dot(segmentDirection)
        .div(segmentLengthSquared)
        .coerceIn(0.0, 1.0)

    private fun approach(
        firstStart: Vector3dc,
        firstDirection: Vector3dc,
        secondStart: Vector3dc,
        secondDirection: Vector3dc,
        firstFraction: Double,
        secondFraction: Double
    ): SegmentApproach {
        val separation = Vector3d(firstStart)
            .fma(firstFraction, firstDirection)
            .sub(Vector3d(secondStart).fma(secondFraction, secondDirection))
        return SegmentApproach(firstFraction, secondFraction, separation.lengthSquared(), parallel = false)
    }

    private fun interpolate(start: Double, end: Double, fraction: Double): Double =
        start + (end - start) * fraction

    private data class Sample(val position: Vector3dc, val arcLength: Double)

    private data class SampledRail(val samples: List<Sample>, val bounds: Bounds)

    private data class Bounds(
        val minX: Double,
        val minY: Double,
        val minZ: Double,
        val maxX: Double,
        val maxY: Double,
        val maxZ: Double
    ) {
        fun overlaps(other: Bounds, tolerance: Double): Boolean =
            minX <= other.maxX + tolerance && other.minX <= maxX + tolerance &&
                minY <= other.maxY + tolerance && other.minY <= maxY + tolerance &&
                minZ <= other.maxZ + tolerance && other.minZ <= maxZ + tolerance

        companion object {
            fun of(samples: List<Sample>): Bounds {
                require(samples.isNotEmpty())
                return Bounds(
                    samples.minOf { it.position.x() },
                    samples.minOf { it.position.y() },
                    samples.minOf { it.position.z() },
                    samples.maxOf { it.position.x() },
                    samples.maxOf { it.position.y() },
                    samples.maxOf { it.position.z() }
                )
            }
        }
    }

    private data class SegmentApproach(
        val firstFraction: Double,
        val secondFraction: Double,
        val distanceSquared: Double,
        val parallel: Boolean
    )

    private data class RailPair(val first: UUID, val second: UUID) {
        companion object {
            fun of(first: UUID, second: UUID): RailPair =
                if (first.toString() < second.toString()) RailPair(first, second) else RailPair(second, first)
        }
    }

    private companion object {
        const val SEGMENT_EPSILON = 1.0e-12
        const val PARALLEL_EPSILON = 1.0e-12
    }
}
