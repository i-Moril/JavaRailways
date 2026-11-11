package work.art1st.javarailways.simplesignals.authoring

import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.simplesignals.block.FixedBlockTraversal
import work.art1st.javarailways.simplesignals.region.ProtectionBoundaryDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionBoundaryCut
import work.art1st.javarailways.simplesignals.region.RailCutPosition
import work.art1st.javarailways.simplesignals.region.ProtectionRegionInteriorSlice

internal data class SectionBoundaryComponent(
    val boundaries: List<ProtectionBoundaryDefinition>,
    val interior: List<ProtectionRegionInteriorSlice>,
    val blockPath: List<FixedBlockTraversal>? = null
)

/** Low-frequency local cut search. Authoring directions are derived, never supplied by sight. */
internal class SectionBoundaryComponents(
    private val topology: TopologyState,
    boundaries: List<ProtectionBoundaryCut>,
    private val maxDistanceMeters: Double
) {
    // Adjacent owners and pending markers may name the same geometric cut.
    val cuts = boundaries.distinctBy { it.position.railId to it.position.arcLengthInAbMeters }
    private val regionCuts = cuts.filter { it.position.arcLengthInAbMeters > Curve.TOLERANCE &&
        it.position.arcLengthInAbMeters < topology.railLength(it.position.railId) - Curve.TOLERANCE }

    fun beside(cut: ProtectionBoundaryCut): List<SectionBoundaryComponent> = ViewDirection.entries.mapNotNull { facing ->
        val seed = cut.facing(facing)
        val target = BlockPathBuilder.nearestBoundary(topology, seed.position, cuts.map { it.position })
        if (target != null) plain(seed, target)
        else if (regionCuts.any { samePoint(it.position, seed.position) }) {
            val result = ProtectionBoundarySearch.searchCuts(topology, seed,
                regionCuts, maxDistanceMeters)
            result.takeIf { it.complete }?.let { SectionBoundaryComponent(it.boundaries, it.interior) }
        } else null
    }.distinctBy { it.interior.toSet() }

    fun containing(point: RailCutPosition): SectionBoundaryComponent? {
        val first = BlockPathBuilder.nearestBoundary(topology, point.facing(ViewDirection.AB), cuts.map { it.position })
        val second = BlockPathBuilder.nearestBoundary(topology, point.facing(ViewDirection.BA), cuts.map { it.position })
        if (first != null && second != null && !samePoint(first, second)) {
            return plain(cuts.first { samePoint(it.position, first) }.facing(first.facing), second)
        }
        // A boundary-radius D component has at most a 2D radius from any interior point.
        // Recheck from the derived boundary below so this does not loosen the configured limit.
        val result = ProtectionBoundarySearch.searchInterior(topology, point, regionCuts, maxDistanceMeters * 2.0)
        if (!result.complete || result.boundaries.isEmpty()) return null
        val checked = ProtectionBoundarySearch.searchCuts(topology, result.boundaries.first(),
            regionCuts, maxDistanceMeters)
        return checked.takeIf { it.complete }?.let { SectionBoundaryComponent(it.boundaries, it.interior) }
    }

    private fun plain(first: ProtectionBoundaryDefinition, second: RailPositionSnapshot): SectionBoundaryComponent {
        val path = BlockPathBuilder.directedPath(topology, first.position, second)
        val end = cuts.first { samePoint(it.position, second) }.facing(second.facing)
        return SectionBoundaryComponent(listOf(first, end),
            path.map { ProtectionRegionInteriorSlice(it.railId, it.lowerAbMeters, it.upperAbMeters) }, path)
    }
}
