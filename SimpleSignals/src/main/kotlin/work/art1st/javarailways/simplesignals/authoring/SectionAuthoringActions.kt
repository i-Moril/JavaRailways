package work.art1st.javarailways.simplesignals.authoring

import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
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

internal data class SectionAuthoringEdit(val patch: SimpleSignalsDefinitionPatch, val message: String)

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
            "Created ${if (block.bidirectional) "bidirectional" else "one-way"} block ${block.blockId}")
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
            "Removed ${ids.size} block(s) and their signals/attached displays; protection boundaries retained")
    }

    fun deleteRegionBoundary(definitions: SimpleSignalsWorldDefinitions, point: RailPositionSnapshot): SectionAuthoringEdit {
        val cut = markers(definitions).filter { samePoint(it.position, point, SNAP_METERS) }
            .minByOrNull { abs(it.position.arcLengthInAbMeters - point.arcLengthInAbMeters) }
            ?: throw IllegalArgumentException("No protection boundary at this position")
        require(definitions.fixedBlocks.none { blockEnds(it).any { end -> samePoint(end, cut.position) } }) {
            "Delete the overlapping block boundary first with the block tool"
        }
        val owner = definitions.regions.singleOrNull { cut in it.effectiveBoundaries }
        return if (owner == null) SectionAuthoringEdit(SimpleSignalsDefinitionPatch(
            pendingProtectionBoundaries = definitions.pendingProtectionBoundaries.filter { it.boundaryId != cut.boundaryId }),
            "Removed pending protection boundary")
        else SectionAuthoringEdit(SimpleSignalsDefinitionPatch(upsertRegions = listOf(ProtectionRegionDefinition(
            owner.regionId, owner.revision + 1, owner.boundaries, owner.effectiveBoundaries.filter { it.boundaryId != cut.boundaryId }))),
            "Protection region ${owner.regionId} is unavailable; its remaining boundaries, routes and devices are retained")
    }

    fun placeRegionBoundary(topology: TopologyState, definitions: SimpleSignalsWorldDefinitions,
        point: RailPositionSnapshot, maxDistanceMeters: Double,
        retainedInteriors: Map<String, List<ProtectionRegionInteriorSlice>>,
        newId: () -> String = { "simplesignals:${UUID.randomUUID()}" }): SectionAuthoringEdit {
        val retainedCut = definitions.regions.filter { it.boundaryDraft != null }.flatMap { it.boundaries }
            .filter { it.insideFacing == point.facing && samePoint(it.position, point, SNAP_METERS) }
            .minByOrNull { abs(it.position.arcLengthInAbMeters - point.arcLengthInAbMeters) }
        val snapped = retainedCut?.position ?: snap(definitions, point)
        require(snapped.arcLengthInAbMeters > Curve.TOLERANCE &&
            snapped.arcLengthInAbMeters < topology.railLength(snapped.railId) - Curve.TOLERANCE) {
            "Place the protection boundary inside a rail, away from its endpoint"
        }
        val existing = markers(definitions)
        require(existing.none { samePoint(it.position, snapped) }) { "A protection boundary already occupies this position" }
        // Restoring an accepted cut restores its stable boundary identity and every device reference.
        val original = definitions.regions.flatMap { it.boundaries }
            .filter { samePoint(it.position, snapped) && it.insideFacing == snapped.facing }
        require(original.size <= 1) { "Boundary position has ambiguous established ownership" }
        val cut = original.singleOrNull() ?: ProtectionBoundaryDefinition(newId(), snapped)
        val search = ProtectionBoundarySearch.search(topology, cut, existing + cut, maxDistanceMeters)
        val foundIds = search.boundaries.map { it.boundaryId }.toSet()
        val owners = definitions.regions.filter { region ->
            region.boundaries.any { it.boundaryId == cut.boundaryId } ||
                region.effectiveBoundaries.any { it.boundaryId in foundIds } ||
                overlaps(search.interior, retainedInteriors[region.regionId].orEmpty())
        }
        require(owners.size <= 1) { "These boundaries would merge established protection regions; edit their cuts separately" }
        val owner = owners.singleOrNull()
        if (!search.complete) {
            // A new cut is durable even while open. Existing ownership is retained independently
            // of whether the bounded search reached any of the old remaining cuts.
            return if (owner == null) SectionAuthoringEdit(SimpleSignalsDefinitionPatch(
                pendingProtectionBoundaries = definitions.pendingProtectionBoundaries + cut), search.detail)
            else SectionAuthoringEdit(SimpleSignalsDefinitionPatch(upsertRegions = listOf(ProtectionRegionDefinition(
                owner.regionId, owner.revision + 1, owner.boundaries, owner.effectiveBoundaries + cut))),
                search.detail + "; existing region remains unavailable")
        }
        require(owner == null || owner.effectiveBoundaries.all { it.boundaryId in foundIds }) {
            "The closed component does not include every remaining boundary of the existing region"
        }
        val region = ProtectionRegionDefinition(owner?.regionId ?: newId(), (owner?.revision ?: -1) + 1, search.boundaries)
        return SectionAuthoringEdit(SimpleSignalsDefinitionPatch(upsertRegions = listOf(region),
            pendingProtectionBoundaries = definitions.pendingProtectionBoundaries.filter { it.boundaryId !in foundIds }),
            if (owner == null) "Created protection region ${region.regionId}" else "Revalidated protection region ${region.regionId}; retained routes and devices")
    }

    private fun overlaps(first: List<ProtectionRegionInteriorSlice>, second: List<ProtectionRegionInteriorSlice>): Boolean {
        val byRail = second.groupBy { it.railId }
        return first.any { a -> byRail[a.railId].orEmpty().any { b ->
            min(a.endAbMeters, b.endAbMeters) - max(a.startAbMeters, b.startAbMeters) > Curve.TOLERANCE
        } }
    }
}
