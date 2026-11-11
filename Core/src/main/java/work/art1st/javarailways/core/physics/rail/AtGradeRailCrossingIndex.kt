package work.art1st.javarailways.core.physics.rail

import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.javarailways.core.api.physics.rail.topology.AtGradeRailCrossing
import work.art1st.javarailways.core.api.physics.rail.topology.RailCrossingPoint
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/** Maximum three-dimensional separation still treated as one approximate at-grade crossing. */
internal const val AT_GRADE_CROSSING_TOLERANCE_METERS = 0.2

/** Incremental structural-edit index of geometry crossings; it is never queried from the tick path. */
internal class AtGradeRailCrossingIndex(
    private val spatialIndex: RailSpatialIndex
) {
    private val crossingsByPair = linkedMapOf<RailPair, AtGradeRailCrossing>()
    private val sampledRails = hashMapOf<UUID, SampledRail>()
    private val exactDetectionCounter = AtomicLong()

    /** Synchronous bootstrap path used while restoring a world before live physics starts. */
    fun addOrReplace(rail: CoreRail) {
        remove(rail)
        val addedGeometry = prepareGeometry(rail.id, rail.curve)
        sampledRails[rail.id] = addedGeometry
        spatialIndex.addRail(rail, addedGeometry.footprint)
        spatialIndex.candidatesFor(rail)
            .sortedBy { it.id.toString() }
            .forEach { candidate ->
                val candidateGeometry = checkNotNull(sampledRails[candidate.id]) {
                    "spatial candidate ${candidate.id} has no sampled crossing geometry"
                }
                detectPair(addedGeometry, candidateGeometry)?.let { crossing ->
                    crossingsByPair[RailPair.of(rail.id, candidate.id)] = crossing
                }
            }
    }

    /** Removes crossing geometry and its spatial footprint symmetrically for one rail. */
    fun remove(rail: CoreRail) {
        crossingsByPair.keys.removeIf { it.first == rail.id || it.second == rail.id }
        sampledRails.remove(rail.id)
        spatialIndex.removeRail(rail)
    }

    fun snapshot(): List<AtGradeRailCrossing> = crossingsByPair.entries
        .sortedWith(compareBy({ it.key.first.toString() }, { it.key.second.toString() }))
        .map { it.value }

    /** Constant-time validation hook for one crossing pair named by a prepared edit delta. */
    fun crossing(pair: RailPair): AtGradeRailCrossing? = crossingsByPair[pair]

    /** Samples one immutable curve and computes its spatial footprint without reading live world state. */
    fun prepareGeometry(railId: UUID, curve: work.art1st.javarailways.core.api.physics.curve.PrimeCurve): SampledRail {
        val samples = simplifyCollinear(
            curve.geometryBreakpoints().map { point -> Sample(Vector3d(point.position), point.arcLength) }
        )
        return SampledRail(
            railId = railId,
            samples = samples,
            bounds = Bounds.of(samples),
            footprint = spatialIndex.prepareFootprint(samples.map(Sample::position))
        )
    }

    /**
     * Copies only existing geometries selected by the spatial broad phase. The caller holds the
     * world topology read lock, so the IDs and samples belong to one published topology root.
     */
    fun captureCandidateSnapshot(
        newRails: List<SampledRail>,
        excludedExistingRailIds: Set<UUID>
    ): CandidateSnapshot {
        val existingByNewRail = linkedMapOf<UUID, List<SampledRail>>()
        newRails.forEach { prepared ->
            existingByNewRail[prepared.railId] = spatialIndex.candidatesFor(prepared.footprint)
                .asSequence()
                .filterNot { it.id in excludedExistingRailIds }
                .sortedBy { it.id.toString() }
                .map { candidate ->
                    checkNotNull(sampledRails[candidate.id]) {
                        "spatial candidate ${candidate.id} has no sampled crossing geometry"
                    }
                }
                .toList()
        }
        return CandidateSnapshot(existingByNewRail)
    }

    /** Runs the exact phase without reading or mutating the live indexes. */
    fun detectPreparedCrossings(
        newRails: List<SampledRail>,
        candidates: CandidateSnapshot
    ): List<AtGradeRailCrossing> {
        val result = linkedMapOf<RailPair, AtGradeRailCrossing>()
        newRails.forEach { added ->
            candidates.existingRailsByNewRailId.getValue(added.railId).forEach { existing ->
                detectPair(added, existing)?.let { crossing ->
                    result[RailPair.of(added.railId, existing.railId)] = crossing
                }
            }
        }
        val priorNewRailsByCell = hashMapOf<RailSpatialIndex.CellKey, MutableList<SampledRail>>()
        newRails.forEach { added ->
            val candidateNewRails = linkedMapOf<UUID, SampledRail>()
            added.footprint.cells.forEach { cell ->
                RailSpatialIndex.neighbourCells(cell).forEach { key ->
                    priorNewRailsByCell[key]?.forEach { candidate ->
                        candidateNewRails.putIfAbsent(candidate.railId, candidate)
                    }
                }
            }
            candidateNewRails.values.forEach { existingNewRail ->
                detectPair(existingNewRail, added)?.let { crossing ->
                    result[RailPair.of(existingNewRail.railId, added.railId)] = crossing
                }
            }
            added.footprint.cells.forEach { cell ->
                priorNewRailsByCell.getOrPut(cell) { mutableListOf() } += added
            }
        }
        return result.entries
            .sortedWith(compareBy({ it.key.first.toString() }, { it.key.second.toString() }))
            .map(Map.Entry<RailPair, AtGradeRailCrossing>::value)
    }

    /** Installs pre-sampled geometry and precomputed crossings; this method never runs exact detection. */
    fun installPrepared(
        railsById: Map<UUID, CoreRail>,
        preparedRails: List<SampledRail>,
        crossings: List<AtGradeRailCrossing>
    ) {
        preparedRails.forEach { prepared ->
            val rail = checkNotNull(railsById[prepared.railId]) {
                "missing prepared rail ${prepared.railId} during crossing-index install"
            }
            check(prepared.railId !in sampledRails) { "rail ${prepared.railId} is already indexed" }
            sampledRails[prepared.railId] = prepared
            spatialIndex.addRail(rail, prepared.footprint)
        }
        crossings.forEach { crossing ->
            val pair = RailPair.of(crossing.first.railId, crossing.second.railId)
            check(pair.first in railsById || pair.second in railsById) {
                "prepared crossing $pair does not involve the installed batch"
            }
            crossingsByPair[pair] = crossing
        }
    }

    internal fun exactDetectionCount(): Long = exactDetectionCounter.get()

    private fun detectPair(first: SampledRail, second: SampledRail): AtGradeRailCrossing? {
        if (!first.bounds.overlaps(second.bounds, AT_GRADE_CROSSING_TOLERANCE_METERS)) return null
        val pair = RailPair.of(first.railId, second.railId)
        val orderedFirst = if (pair.first == first.railId) first else second
        val orderedSecond = if (orderedFirst === first) second else first
        return detect(
            orderedFirst.railId,
            orderedFirst.samples,
            orderedSecond.railId,
            orderedSecond.samples
        )
    }

    private fun detect(
        firstRailId: UUID,
        firstSamples: List<Sample>,
        secondRailId: UUID,
        secondSamples: List<Sample>
    ): AtGradeRailCrossing? {
        exactDetectionCounter.incrementAndGet()
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
                    RailCrossingPoint(firstRailId, firstArc),
                    RailCrossingPoint(secondRailId, secondArc)
                )
            }
        }
        return bestCrossing?.takeUnless { bestApproachIsParallel }
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

    internal data class CandidateSnapshot(
        val existingRailsByNewRailId: Map<UUID, List<SampledRail>>
    )

    internal data class Sample(val position: Vector3dc, val arcLength: Double)

    internal data class SampledRail(
        val railId: UUID,
        val samples: List<Sample>,
        val bounds: Bounds,
        val footprint: RailSpatialIndex.PreparedFootprint
    )

    internal data class Bounds(
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

    internal data class RailPair(val first: UUID, val second: UUID) {
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
