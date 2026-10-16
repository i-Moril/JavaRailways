package work.art1st.javarailways.simplesignals.region

import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

internal data class ProtectionRegionInteriorSlice(
    val railId: UUID,
    val startAbMeters: Double,
    val endAbMeters: Double
)

internal data class ResolvedProtectionRegion(
    val definition: ProtectionRegionDefinition,
    val interior: List<ProtectionRegionInteriorSlice>
)

internal sealed interface ProtectionRegionGraphResolution {
    data class Resolved(val region: ResolvedProtectionRegion) : ProtectionRegionGraphResolution
    data class Rejected(val diagnostic: ProtectionRegionDiagnostic) : ProtectionRegionGraphResolution
}

/** Pure graph-cut resolver. It reads one revision-bound Core topology derivation and performs no mutation. */
internal object ProtectionRegionGraphResolver {
    fun resolve(
        definition: ProtectionRegionDefinition,
        topology: ProtectionRegionTopology
    ): ProtectionRegionGraphResolution {
        val cuts = linkedMapOf<UUID, MutableList<ProtectionBoundaryDefinition>>()
        for (boundary in definition.boundaries) {
            val rail = topology.rails[boundary.position.railId]
                ?: return rejected(
                    code = ProtectionRegionValidationCode.MISSING_RAIL,
                    detail = "boundary ${boundary.boundaryId} references missing rail " +
                        boundary.position.railId,
                    definition = definition,
                    boundary = boundary
                )
            val arc = boundary.position.arcLengthInAbMeters
            if (arc <= Curve.TOLERANCE || arc >= rail.lengthMeters - Curve.TOLERANCE) {
                return rejected(
                    code = ProtectionRegionValidationCode.BOUNDARY_AT_RAIL_ENDPOINT,
                    detail = "boundary ${boundary.boundaryId} must lie strictly inside rail ${rail.railId}",
                    definition = definition,
                    boundary = boundary
                )
            }
            cuts.getOrPut(rail.railId, ::mutableListOf).add(boundary)
        }
        cuts.values.forEach { boundaries ->
            boundaries.sortBy { it.position.arcLengthInAbMeters }
            boundaries.zipWithNext().firstOrNull { (first, second) ->
                second.position.arcLengthInAbMeters - first.position.arcLengthInAbMeters <= Curve.TOLERANCE
            }?.let { (first, second) ->
                return rejected(
                    code = ProtectionRegionValidationCode.DUPLICATE_BOUNDARY_POSITION,
                    detail = "boundaries ${first.boundaryId} and ${second.boundaryId} occupy the same rail cut",
                    definition = definition,
                    boundary = second
                )
            }
        }

        val cutPositions = cuts.mapValues { (_, boundaries) ->
            boundaries.map { it.position.arcLengthInAbMeters }
        }
        val firstBoundary = definition.boundaries.first()
        val firstInside = insideSegment(firstBoundary, cutPositions)
        val interiorSegments = component(firstInside, cutPositions, topology)

        for (boundary in definition.boundaries) {
            val inside = insideSegment(boundary, cutPositions)
            if (inside !in interiorSegments) {
                return rejected(
                    code = ProtectionRegionValidationCode.DISCONNECTED_INTERIOR,
                    detail = "boundary ${boundary.boundaryId} enters a different interior component",
                    definition = definition,
                    boundary = boundary
                )
            }
            val outside = outsideSegment(boundary, cutPositions)
            if (outside in interiorSegments) {
                return rejected(
                    code = ProtectionRegionValidationCode.OUTSIDE_RECONNECTS,
                    detail = "boundary ${boundary.boundaryId} reconnects to its outside through the topology",
                    definition = definition,
                    boundary = boundary
                )
            }
        }

        val interior = interiorSegments.map { segment -> segment.toSlice(cutPositions, topology) }
            .sortedWith(compareBy(ProtectionRegionInteriorSlice::railId, ProtectionRegionInteriorSlice::startAbMeters))
        return ProtectionRegionGraphResolution.Resolved(
            ResolvedProtectionRegion(definition, interior)
        )
    }

    fun overlap(
        first: ResolvedProtectionRegion,
        second: ResolvedProtectionRegion
    ): ProtectionRegionDiagnostic? {
        val secondByRail = second.interior.groupBy(ProtectionRegionInteriorSlice::railId)
        for (firstSlice in first.interior) {
            for (secondSlice in secondByRail[firstSlice.railId].orEmpty()) {
                val overlapStart = max(firstSlice.startAbMeters, secondSlice.startAbMeters)
                val overlapEnd = min(firstSlice.endAbMeters, secondSlice.endAbMeters)
                if (overlapEnd - overlapStart > Curve.TOLERANCE) {
                    return ProtectionRegionDiagnostic(
                        code = ProtectionRegionValidationCode.REGION_OVERLAP,
                        detail = "regions ${first.definition.regionId} and " +
                            "${second.definition.regionId} overlap on rail ${firstSlice.railId}",
                        regionId = second.definition.regionId,
                        railId = firstSlice.railId
                    )
                }
            }
        }
        return null
    }

    private fun component(
        start: SegmentKey,
        cuts: Map<UUID, List<Double>>,
        topology: ProtectionRegionTopology
    ): Set<SegmentKey> {
        val visited = linkedSetOf<SegmentKey>()
        val queue = ArrayDeque<SegmentKey>()
        queue.add(start)
        while (queue.isNotEmpty()) {
            val segment = queue.removeFirst()
            if (!visited.add(segment)) continue
            val rail = topology.rails.getValue(segment.railId)
            val lastSegmentIndex = cuts[segment.railId].orEmpty().size
            if (segment.index == 0) {
                rail.connectionsFromA.forEach { connection ->
                    queue.add(connection.entrySegment(cuts))
                }
            }
            if (segment.index == lastSegmentIndex) {
                rail.connectionsFromB.forEach { connection ->
                    queue.add(connection.entrySegment(cuts))
                }
            }
            // Crossing conflicts belong to one enclosure even though trains cannot turn there.
            // Keep these links local to the cut resolver, never in physical connections/routes.
            val slice = segment.toSlice(cuts, topology)
            topology.topologyState.atGradeCrossingsOn(segment.railId).forEach { crossing ->
                val own = if (crossing.first.railId == segment.railId) crossing.first else crossing.second
                if (own.arcLengthAbMeters < slice.startAbMeters || own.arcLengthAbMeters > slice.endAbMeters) {
                    return@forEach
                }
                val other = if (own == crossing.first) crossing.second else crossing.first
                val otherCuts = cuts[other.railId].orEmpty()
                val cutIndex = otherCuts.binarySearch(other.arcLengthAbMeters)
                if (cutIndex >= 0) {
                    // A cut on the conflict point cannot split ownership of the crossing.
                    queue.add(SegmentKey(other.railId, cutIndex))
                    queue.add(SegmentKey(other.railId, cutIndex + 1))
                } else {
                    queue.add(SegmentKey(other.railId, -cutIndex - 1))
                }
            }
        }
        return visited
    }

    private fun ProtectionTopologyConnection.entrySegment(cuts: Map<UUID, List<Double>>): SegmentKey =
        SegmentKey(
            railId,
            if (enteringFacing == ViewDirection.AB) 0 else cuts[railId].orEmpty().size
        )

    private fun insideSegment(
        boundary: ProtectionBoundaryDefinition,
        cuts: Map<UUID, List<Double>>
    ): SegmentKey {
        val cutIndex = cuts.getValue(boundary.position.railId)
            .indexOf(boundary.position.arcLengthInAbMeters)
        return SegmentKey(
            boundary.position.railId,
            if (boundary.insideFacing == ViewDirection.AB) cutIndex + 1 else cutIndex
        )
    }

    private fun outsideSegment(
        boundary: ProtectionBoundaryDefinition,
        cuts: Map<UUID, List<Double>>
    ): SegmentKey {
        val cutIndex = cuts.getValue(boundary.position.railId)
            .indexOf(boundary.position.arcLengthInAbMeters)
        return SegmentKey(
            boundary.position.railId,
            if (boundary.insideFacing == ViewDirection.AB) cutIndex else cutIndex + 1
        )
    }

    private fun SegmentKey.toSlice(
        cuts: Map<UUID, List<Double>>,
        topology: ProtectionRegionTopology
    ): ProtectionRegionInteriorSlice {
        val railCuts = cuts[railId].orEmpty()
        val railLength = topology.rails.getValue(railId).lengthMeters
        return ProtectionRegionInteriorSlice(
            railId,
            startAbMeters = if (index == 0) 0.0 else railCuts[index - 1],
            endAbMeters = if (index == railCuts.size) railLength else railCuts[index]
        )
    }

    private fun rejected(
        code: ProtectionRegionValidationCode,
        detail: String,
        definition: ProtectionRegionDefinition? = null,
        boundary: ProtectionBoundaryDefinition? = null,
        regionId: String? = definition?.regionId
    ) = ProtectionRegionGraphResolution.Rejected(
        ProtectionRegionDiagnostic(
            code = code,
            detail = detail,
            regionId = regionId,
            boundaryId = boundary?.boundaryId,
            railId = boundary?.position?.railId
        )
    )

    private data class SegmentKey(val railId: UUID, val index: Int)
}
