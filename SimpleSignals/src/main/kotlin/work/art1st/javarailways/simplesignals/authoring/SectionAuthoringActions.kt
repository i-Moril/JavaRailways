package work.art1st.javarailways.simplesignals.authoring

import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNode
import work.art1st.javarailways.simplesignals.block.FixedBlockDefinition
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitions
import work.art1st.javarailways.simplesignals.region.ProtectionBoundaryDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionRegionDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionRegionInteriorSlice
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsDefinitionPatch
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class SectionAuthoringEdit(val patch: SimpleSignalsDefinitionPatch, val text: WandText) {
    constructor(patch: SimpleSignalsDefinitionPatch, message: String) : this(patch, WandText.diagnostic(message))
}

/** Pure patch construction. All mutation, permits, persistence and publication belong to the manager. */
internal object SectionAuthoringActions {
    const val SNAP_METERS = 0.75

    fun blockEnds(block: FixedBlockDefinition): List<RailPositionSnapshot> = listOf(
        block.traversals.first().let { RailPositionSnapshot(it.railId, it.facing, it.travelStartAbMeters) },
        block.traversals.last().let { RailPositionSnapshot(it.railId, it.facing.reversed(), it.travelEndAbMeters) })

    fun markers(definitions: SimpleSignalsWorldDefinitions): List<ProtectionBoundaryDefinition> =
        definitions.pendingProtectionBoundaries + definitions.regions.flatMap { it.effectiveBoundaries }

    fun snap(definitions: SimpleSignalsWorldDefinitions, point: RailPositionSnapshot): RailPositionSnapshot {
        val positions = definitions.fixedBlocks.flatMap(::blockEnds) + markers(definitions).map { it.position }
        return positions.filter { samePoint(it, point, SNAP_METERS) }
            .minByOrNull { abs(it.arcLengthInAbMeters - point.arcLengthInAbMeters) }
            ?.copy(facing = point.facing) ?: point
    }

    fun selectBlockEnd(definitions: SimpleSignalsWorldDefinitions, point: RailPositionSnapshot,
        topology: TopologyState? = null): RailPositionSnapshot =
        definitions.fixedBlocks.flatMap(::blockEnds).filter { samePoint(it, point, SNAP_METERS) || topology?.sameBlockBoundary(it, point) == true }
            .minByOrNull { if (it.railId == point.railId) abs(it.arcLengthInAbMeters - point.arcLengthInAbMeters) else 0.0 }
            ?: throw IllegalArgumentException("Left-click an existing block boundary, or right-click to place a new point")

    fun createBlock(definitions: SimpleSignalsWorldDefinitions, block: FixedBlockDefinition): SectionAuthoringEdit {
        require(definitions.fixedBlocks.none { existing -> existing.traversals.any { a -> block.traversals.any { b ->
            a.railId == b.railId && min(a.upperAbMeters, b.upperAbMeters) - max(a.lowerAbMeters, b.lowerAbMeters) > Curve.TOLERANCE
        } } }) { "Block interior overlaps an existing block" }
        return SectionAuthoringEdit(SimpleSignalsDefinitionPatch(upsertFixedBlocks = listOf(block)),
            WandText.key("block.created", WandText.key(if (block.bidirectional) "block.bidirectional" else "block.one-way"), authoringName(block.blockId, "name.block")))
    }

    fun deleteBlock(definitions: SimpleSignalsWorldDefinitions, point: RailPositionSnapshot, boundary: Boolean,
        topology: TopologyState? = null): SectionAuthoringEdit {
        val selected = if (boundary) {
            val end = selectBlockEnd(definitions, point, topology)
            definitions.fixedBlocks.filter { blockEnds(it).any { endPoint ->
                samePoint(endPoint, end) || topology?.sameBlockBoundary(endPoint, end) == true } }
        } else definitions.fixedBlocks.filter { block -> block.traversals.any {
            it.railId == point.railId && point.arcLengthInAbMeters > it.lowerAbMeters + Curve.TOLERANCE &&
                point.arcLengthInAbMeters < it.upperAbMeters - Curve.TOLERANCE
        } }.also { require(it.size <= 1) { "Several blocks overlap this body; select a boundary instead" } }
        require(selected.isNotEmpty()) { "No block at this position" }
        val ids = selected.map { it.blockId }.toSet()
        val signals = definitions.fixedBlockSignals.filter { it.controlledBlockId in ids }.map { it.signalId }.toSet()
        return SectionAuthoringEdit(SimpleSignalsDefinitionPatch(
            removeFixedBlockIds = ids,
            removeFixedBlockSignalIds = signals,
            removeAttachedSignalIds = definitions.attachedSignals.filter { it.primarySignalId in signals }.map { it.signalId }),
            WandText.key("block.removed", ids.size))
    }

    fun deleteRegionBoundary(definitions: SimpleSignalsWorldDefinitions, point: RailPositionSnapshot): SectionAuthoringEdit {
        val candidates = markers(definitions).filter { it.insideFacing == point.facing && samePoint(it.position, point, SNAP_METERS) }
        val distance = candidates.minOfOrNull { abs(it.position.arcLengthInAbMeters - point.arcLengthInAbMeters) }
            ?: throw IllegalArgumentException("No boundary faces this direction at this position")
        val cut = candidates.filter { abs(abs(it.position.arcLengthInAbMeters - point.arcLengthInAbMeters) - distance) <= Curve.TOLERANCE }
            .singleOrNull() ?: throw IllegalArgumentException("Several boundaries match this position and direction")
        require(definitions.fixedBlocks.none { blockEnds(it).any { end -> samePoint(end, cut.position) } }) {
            "Left-click to delete the adjoining block before removing this protection boundary"
        }
        val owner = definitions.regions.singleOrNull { cut in it.effectiveBoundaries }
        return if (owner == null) SectionAuthoringEdit(SimpleSignalsDefinitionPatch(
            pendingProtectionBoundaries = definitions.pendingProtectionBoundaries.filter { it.boundaryId != cut.boundaryId }),
            WandText.key("boundary.removed"))
        else SectionAuthoringEdit(SimpleSignalsDefinitionPatch(upsertRegions = listOf(ProtectionRegionDefinition(
            owner.regionId, owner.revision + 1, owner.boundaries, owner.effectiveBoundaries.filter { it.boundaryId != cut.boundaryId }))),
            WandText.key("region.unavailable", authoringName(owner.regionId, "name.region")))
    }

    fun deleteSection(definitions: SimpleSignalsWorldDefinitions, point: RailPositionSnapshot,
        boundary: Boolean, topology: TopologyState): SectionAuthoringEdit {
        val atBlock = definitions.fixedBlocks.any { block ->
            if (boundary) blockEnds(block).any { samePoint(it, point, SNAP_METERS) || topology.sameBlockBoundary(it, point) }
            else block.traversals.any { it.railId == point.railId &&
                point.arcLengthInAbMeters > it.lowerAbMeters + Curve.TOLERANCE &&
                point.arcLengthInAbMeters < it.upperAbMeters - Curve.TOLERANCE }
        }
        return if (atBlock) deleteBlock(definitions, point, boundary, topology) else deleteRegionBoundary(definitions, point)
    }

    fun placeSectionBoundary(topology: TopologyState, definitions: SimpleSignalsWorldDefinitions,
        point: RailPositionSnapshot, maxDistanceMeters: Double,
        retainedInteriors: Map<String, List<ProtectionRegionInteriorSlice>>,
        directions: (Set<UUID>) -> Map<UUID, ViewDirection?>,
        newId: () -> String = { "simplesignals:${UUID.randomUUID()}" }): SectionAuthoringEdit {
        val retainedCut = definitions.regions.filter { it.boundaryDraft != null }.flatMap { it.boundaries }
            .filter { it.insideFacing == point.facing && samePoint(it.position, point, SNAP_METERS) }
            .minByOrNull { abs(it.position.arcLengthInAbMeters - point.arcLengthInAbMeters) }
        val snapped = retainedCut?.position ?: snap(definitions, point)
        require(snapped.arcLengthInAbMeters <= topology.railLength(snapped.railId)) { "Boundary lies beyond its rail" }
        val existing = markers(definitions)
        // Restoring an accepted cut restores its stable boundary identity and every device reference.
        val original = definitions.regions.flatMap { it.boundaries }
            .filter { samePoint(it.position, snapped) && it.insideFacing == snapped.facing }
        require(original.size <= 1) { "Boundary position has ambiguous established ownership" }
        require(existing.none { samePoint(it.position, snapped) && it.insideFacing == snapped.facing }) {
            "A boundary already occupies this position and direction"
        }
        val cut = original.singleOrNull() ?: ProtectionBoundaryDefinition(newId(), snapped)
        // Published block/region borders are read-only barriers for a neighbouring component.
        // Pending cuts retain the direction explicitly chosen by their author.
        val blockCuts = definitions.fixedBlocks.flatMap { block -> blockEnds(block).mapIndexed { index, end ->
            ProtectionBoundaryDefinition("${block.blockId}/boundary-$index", end)
        } }
        val barriers = (existing + blockCuts).distinctBy { it.position.railId to it.position.arcLengthInAbMeters }
        val establishedIds = (definitions.regions.flatMap { it.effectiveBoundaries } + blockCuts).map { it.boundaryId }.toSet()
        val repairing = definitions.regions.any { region -> region.boundaries.any { it.boundaryId == cut.boundaryId } &&
            region.boundaryDraft != null } || retainedInteriors.values.any { slices -> slices.any {
            it.railId == snapped.railId && snapped.arcLengthInAbMeters > it.startAbMeters + Curve.TOLERANCE &&
                snapped.arcLengthInAbMeters < it.endAbMeters - Curve.TOLERANCE
        } }
        if (!repairing) {
            val nearest = BlockPathBuilder.nearestBoundary(topology, snapped, barriers.map { it.position })
            if (nearest != null) {
                val other = barriers.first { it.position == nearest }
                val path = BlockPathBuilder.path(topology, snapped, nearest)
                if (path.first().facing == snapped.facing &&
                    (other.boundaryId in establishedIds || path.last().facing.reversed() == other.insideFacing)) {
                    val block = BlockPathBuilder.definition(newId(), path, directions(path.map { it.railId }.toSet()))
                    val made = createBlock(definitions, block)
                    return SectionAuthoringEdit(SimpleSignalsDefinitionPatch(upsertFixedBlocks = listOf(block),
                        pendingProtectionBoundaries = definitions.pendingProtectionBoundaries.filter {
                            it.boundaryId != cut.boundaryId && it.boundaryId != other.boundaryId }), made.text)
                }
            }
        }
        val endpoint = snapped.arcLengthInAbMeters <= Curve.TOLERANCE ||
            snapped.arcLengthInAbMeters >= topology.railLength(snapped.railId) - Curve.TOLERANCE
        if (endpoint) return SectionAuthoringEdit(SimpleSignalsDefinitionPatch(
            pendingProtectionBoundaries = definitions.pendingProtectionBoundaries + cut), WandText.key("section.pending"))
        val regionBarriers = barriers.filter { it.position.arcLengthInAbMeters > Curve.TOLERANCE &&
            it.position.arcLengthInAbMeters < topology.railLength(it.position.railId) - Curve.TOLERANCE &&
            !samePoint(it.position, cut.position) }
        val search = ProtectionBoundarySearch.search(topology, cut, regionBarriers + cut, maxDistanceMeters, establishedIds)
        val foundIds = search.boundaries.map { it.boundaryId }.toSet()
        val owners = definitions.regions.filter { region ->
            region.boundaries.any { it.boundaryId == cut.boundaryId && it.insideFacing == cut.insideFacing } ||
                region.effectiveBoundaries.any { boundary -> search.boundaries.any {
                    it.boundaryId == boundary.boundaryId && it.insideFacing == boundary.insideFacing } } ||
                overlaps(search.interior, retainedInteriors[region.regionId].orEmpty())
        }
        require(owners.size <= 1) { "These boundaries would merge established protection regions; edit their cuts separately" }
        val owner = owners.singleOrNull()
        if (!search.complete) {
            // A new cut is durable even while open. Existing ownership is retained independently
            // of whether the bounded search reached any of the old remaining cuts.
            return if (owner == null) SectionAuthoringEdit(SimpleSignalsDefinitionPatch(
                pendingProtectionBoundaries = definitions.pendingProtectionBoundaries + cut), WandText.key("section.pending.detail", search.detail))
            else SectionAuthoringEdit(SimpleSignalsDefinitionPatch(upsertRegions = listOf(ProtectionRegionDefinition(
                owner.regionId, owner.revision + 1, owner.boundaries, owner.effectiveBoundaries + cut))),
                WandText.key("region.pending", search.detail))
        }
        require(owner == null || owner.effectiveBoundaries.all { it.boundaryId in foundIds }) {
            "The closed component does not include every remaining boundary of the existing region"
        }
        require(owner != null || containsConflict(topology, search.interior)) {
            "A turnout-free section requires two unambiguous inward-facing boundaries"
        }
        val regionBoundaries = search.boundaries.map { boundary ->
            // A neighbouring component keeps its boundary and device identity.
            if (owner == null && boundary.boundaryId in establishedIds)
                boundary.copy(boundaryId = newId()) else boundary
        }
        val region = ProtectionRegionDefinition(owner?.regionId ?: newId(), (owner?.revision ?: -1) + 1, regionBoundaries)
        return SectionAuthoringEdit(SimpleSignalsDefinitionPatch(upsertRegions = listOf(region),
            pendingProtectionBoundaries = definitions.pendingProtectionBoundaries.filter { it.boundaryId !in foundIds }),
            if (owner == null) WandText.key("region.created", authoringName(region.regionId, "name.region")) else
                WandText.key("region.revalidated", authoringName(region.regionId, "name.region")))
    }

    private fun containsConflict(topology: TopologyState, interior: List<ProtectionRegionInteriorSlice>): Boolean = interior.any { slice ->
        topology.crossesConflict(slice.railId, slice.startAbMeters, slice.endAbMeters) ||
            (slice.startAbMeters <= Curve.TOLERANCE && topology.structuralNodeAt(
                RailEndpointRef(slice.railId, RailEndpoint.A)) is TopologyNode.Turnout) ||
            (slice.endAbMeters >= topology.railLength(slice.railId) - Curve.TOLERANCE && topology.structuralNodeAt(
                RailEndpointRef(slice.railId, RailEndpoint.B)) is TopologyNode.Turnout)
    }

    private fun overlaps(first: List<ProtectionRegionInteriorSlice>, second: List<ProtectionRegionInteriorSlice>): Boolean {
        val byRail = second.groupBy { it.railId }
        return first.any { a -> byRail[a.railId].orEmpty().any { b ->
            min(a.endAbMeters, b.endAbMeters) - max(a.startAbMeters, b.startAbMeters) > Curve.TOLERANCE
        } }
    }
}
