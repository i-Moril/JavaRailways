package work.art1st.javarailways.simplesignals.authoring

import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNode
import work.art1st.javarailways.simplesignals.block.FixedBlockDefinition
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitions
import work.art1st.javarailways.simplesignals.region.ProtectionBoundaryDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionBoundaryCut
import work.art1st.javarailways.simplesignals.region.RailCutPosition
import work.art1st.javarailways.simplesignals.region.cutPosition
import work.art1st.javarailways.simplesignals.region.ProtectionRegionDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionRegionInteriorSlice
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsDefinitionPatch
import work.art1st.javarailways.simplesignals.signal.ProtectionSignalKind
import work.art1st.javarailways.simplesignals.signal.RouteIndicatorDefinition
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

    fun markers(definitions: SimpleSignalsWorldDefinitions): List<ProtectionBoundaryCut> =
        definitions.pendingProtectionBoundaries + definitions.regions.flatMap { it.effectiveCuts }

    fun snap(definitions: SimpleSignalsWorldDefinitions, point: RailCutPosition): RailCutPosition {
        val positions = definitions.fixedBlocks.flatMap(::blockEnds).map { it.cutPosition() } + markers(definitions).map { it.position }
        return positions.filter { samePoint(it, point, SNAP_METERS) }
            .minByOrNull { abs(it.arcLengthInAbMeters - point.arcLengthInAbMeters) }
            ?: point
    }

    fun selectBlockEnd(definitions: SimpleSignalsWorldDefinitions, point: RailCutPosition,
        topology: TopologyState? = null): RailPositionSnapshot =
        definitions.fixedBlocks.flatMap(::blockEnds).filter { samePoint(it, point, SNAP_METERS) || topology?.sameBlockBoundary(it.cutPosition(), point) == true }
            .minByOrNull { if (it.railId == point.railId) abs(it.arcLengthInAbMeters - point.arcLengthInAbMeters) else 0.0 }
            ?: throw IllegalArgumentException("Left-click an existing block boundary, or right-click to place a new point")

    fun createBlock(definitions: SimpleSignalsWorldDefinitions, block: FixedBlockDefinition): SectionAuthoringEdit {
        require(definitions.fixedBlocks.none { existing -> existing.traversals.any { a -> block.traversals.any { b ->
            a.railId == b.railId && min(a.upperAbMeters, b.upperAbMeters) - max(a.lowerAbMeters, b.lowerAbMeters) > Curve.TOLERANCE
        } } }) { "Block interior overlaps an existing block" }
        return SectionAuthoringEdit(SimpleSignalsDefinitionPatch(upsertFixedBlocks = listOf(block)),
            WandText.key("block.created", authoringName(block.blockId, "name.block")))
    }

    fun deleteBlock(definitions: SimpleSignalsWorldDefinitions, point: RailCutPosition, boundary: Boolean,
        topology: TopologyState? = null, retainDevices: Boolean): SectionAuthoringEdit {
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
        return SectionAuthoringEdit(removeSections(definitions, ids, emptySet(), retainDevices),
            WandText.key("block.removed", ids.size))
    }

    fun deleteRegion(definitions: SimpleSignalsWorldDefinitions, region: ProtectionRegionDefinition,
        retainDevices: Boolean): SectionAuthoringEdit {
        require(region in definitions.regions) { "The selected protection region changed" }
        return SectionAuthoringEdit(removeSections(definitions, emptySet(), setOf(region.regionId), retainDevices),
            WandText.key("region.removed", authoringName(region.regionId, "name.region")))
    }

    private fun removeSections(definitions: SimpleSignalsWorldDefinitions, blockIds: Set<String>, regionIds: Set<String>,
        retainDevices: Boolean): SimpleSignalsDefinitionPatch {
        val tables = definitions.routeTables.filter { it.regionId in regionIds }
        val routeIds = tables.flatMap { it.routes }.mapTo(hashSetOf()) { it.routeId }
        val entryIds = definitions.regions.filter { it.regionId in regionIds }
            .flatMap { region -> region.boundaries.map { it.boundaryId } + region.effectiveCuts.map { it.boundaryId } }.toSet()
        val fixed = definitions.fixedBlockSignals.filter { it.controlledBlockId in blockIds }
        val protection = definitions.protectionSignals.filter {
            when (it.kind) {
                ProtectionSignalKind.FIXED_ROUTE -> it.bindingId in routeIds
                ProtectionSignalKind.CURRENT_ENTRY -> it.bindingId in entryIds
            }
        }
        val indicators = definitions.routeIndicators.filter { it.entryBoundaryId in entryIds }
        val primaryIds = (fixed.map { it.signalId } + protection.map { it.signalId }).toSet()
        return SimpleSignalsDefinitionPatch(removeFixedBlockIds = blockIds, removeRegionIds = regionIds,
            removeRouteTableIds = tables.map { it.tableId },
            upsertFixedBlockSignals = if (retainDevices) fixed.map { it.copy(revision = Math.addExact(it.revision, 1), controlledBlockId = null) } else emptyList(),
            upsertProtectionSignals = if (retainDevices) protection.map { it.copy(revision = Math.addExact(it.revision, 1), bindingId = null) } else emptyList(),
            upsertRouteIndicators = if (retainDevices) indicators.map { RouteIndicatorDefinition(it.indicatorId, Math.addExact(it.revision, 1),
                null, it.noRouteModelVariant, emptyMap(), it.model) } else emptyList(),
            removeFixedBlockSignalIds = if (retainDevices) emptyList() else fixed.map { it.signalId },
            removeProtectionSignalIds = if (retainDevices) emptyList() else protection.map { it.signalId },
            removeRouteIndicatorIds = if (retainDevices) emptyList() else indicators.map { it.indicatorId },
            removeAttachedSignalIds = if (retainDevices) emptyList() else definitions.attachedSignals.filter { it.primarySignalId in primaryIds }.map { it.signalId })
    }

    fun deleteRegionBoundary(definitions: SimpleSignalsWorldDefinitions, point: RailCutPosition): SectionAuthoringEdit {
        val candidates = markers(definitions).filter { samePoint(it.position, point, SNAP_METERS) }
        val distance = candidates.minOfOrNull { abs(it.position.arcLengthInAbMeters - point.arcLengthInAbMeters) }
            ?: throw IllegalArgumentException("No boundary at this position")
        val nearest = candidates.filter { abs(abs(it.position.arcLengthInAbMeters - point.arcLengthInAbMeters) - distance) <= Curve.TOLERANCE }
        val cut = nearest.first()
        require(nearest.all { samePoint(it.position, cut.position) }) { "Several boundary positions are equally near" }
        val owners = definitions.regions.filter { region -> region.effectiveCuts.any { samePoint(it.position, cut.position) } }
        if (owners.size > 1) return SectionAuthoringEdit(SimpleSignalsDefinitionPatch(), WandText.key("section.delete.ambiguous"))
        require(definitions.fixedBlocks.none { blockEnds(it).any { end -> samePoint(end, cut.position) } }) {
            "Left-click to delete the adjoining block before removing this protection boundary"
        }
        val owner = owners.singleOrNull()
        return if (owner == null) SectionAuthoringEdit(SimpleSignalsDefinitionPatch(
            pendingProtectionBoundaries = definitions.pendingProtectionBoundaries.filterNot { samePoint(it.position, cut.position) }),
            WandText.key("boundary.removed"))
        else SectionAuthoringEdit(SimpleSignalsDefinitionPatch(upsertRegions = listOf(ProtectionRegionDefinition(
            owner.regionId, owner.revision + 1, owner.boundaries, owner.effectiveCuts.filterNot { samePoint(it.position, cut.position) }))),
            WandText.key("region.unavailable", authoringName(owner.regionId, "name.region")))
    }

    fun deleteSection(definitions: SimpleSignalsWorldDefinitions, point: RailCutPosition,
        boundary: Boolean, topology: TopologyState, retainDevices: Boolean): SectionAuthoringEdit {
        val atBlock = definitions.fixedBlocks.any { block ->
            if (boundary) blockEnds(block).any { samePoint(it, point, SNAP_METERS) || topology.sameBlockBoundary(it.cutPosition(), point) }
            else block.traversals.any { it.railId == point.railId &&
                point.arcLengthInAbMeters > it.lowerAbMeters + Curve.TOLERANCE &&
                point.arcLengthInAbMeters < it.upperAbMeters - Curve.TOLERANCE }
        }
        return if (atBlock) deleteBlock(definitions, point, boundary, topology, retainDevices) else deleteRegionBoundary(definitions, point)
    }

    fun placeSectionBoundary(topology: TopologyState, definitions: SimpleSignalsWorldDefinitions,
        point: RailCutPosition, maxDistanceMeters: Double,
        retainedInteriors: Map<String, List<ProtectionRegionInteriorSlice>>,
        newId: () -> String = { "simplesignals:${UUID.randomUUID()}" }): SectionAuthoringEdit {
        val retainedCut = definitions.regions.filter { it.boundaryDraft != null }.flatMap { it.boundaries }
            .filter { samePoint(it.position, point, SNAP_METERS) }
            .minByOrNull { abs(it.position.arcLengthInAbMeters - point.arcLengthInAbMeters) }
        val snapped = retainedCut?.position?.cutPosition() ?: snap(definitions, point)
        require(snapped.arcLengthInAbMeters <= topology.railLength(snapped.railId)) { "Boundary lies beyond its rail" }
        val original = definitions.regions.filter { it.boundaryDraft != null }.flatMap { it.boundaries }
            .filter { samePoint(it.position, snapped) }.singleOrNull()
        val cut = markers(definitions).firstOrNull { samePoint(it.position, snapped) }
            ?: original?.cut() ?: ProtectionBoundaryCut(newId(), snapped)
        val components = components(topology, definitions, maxDistanceMeters, cut).beside(cut)
            .filterNot { occupied(definitions, it, retainedInteriors) }
        if (components.size == 1) return completeSection(topology, definitions, components.single(), retainedInteriors, maxDistanceMeters, newId)
        val draftOwners = definitions.regions.filter { it.boundaryDraft != null &&
            retainedInteriors[it.regionId].orEmpty().any { slice -> slice.railId == snapped.railId &&
                snapped.arcLengthInAbMeters > slice.startAbMeters - Curve.TOLERANCE &&
                snapped.arcLengthInAbMeters < slice.endAbMeters + Curve.TOLERANCE } }
        require(draftOwners.size <= 1) { "This cut would merge established protection regions" }
        val owner = draftOwners.singleOrNull()
        val message = WandText.key(if (components.size > 1) "section.ambiguous" else "section.pending")
        if (owner != null && owner.effectiveCuts.none { samePoint(it.position, snapped) }) {
            return SectionAuthoringEdit(SimpleSignalsDefinitionPatch(upsertRegions = listOf(ProtectionRegionDefinition(
                owner.regionId, owner.revision + 1, owner.boundaries, owner.effectiveCuts + cut))), message)
        }
        return SectionAuthoringEdit(SimpleSignalsDefinitionPatch(pendingProtectionBoundaries =
            if (markers(definitions).any { samePoint(it.position, cut.position) }) null
            else definitions.pendingProtectionBoundaries + cut.copy(position = snapped)), message)
    }

    fun confirmSection(topology: TopologyState, definitions: SimpleSignalsWorldDefinitions,
        point: RailCutPosition, maxDistanceMeters: Double,
        retainedInteriors: Map<String, List<ProtectionRegionInteriorSlice>>,
        newId: () -> String = { "simplesignals:${UUID.randomUUID()}" }): SectionAuthoringEdit {
        val search = components(topology, definitions, maxDistanceMeters)
        if (search.cuts.any { samePoint(it.position, point) || topology.sameBlockBoundary(it.position, point) })
            return SectionAuthoringEdit(SimpleSignalsDefinitionPatch(), WandText.key("section.select.interior"))
        val component = search.containing(point)
            ?: return SectionAuthoringEdit(SimpleSignalsDefinitionPatch(), WandText.key("section.not.closed"))
        require(!occupied(definitions, component, retainedInteriors)) { "This section already exists" }
        return completeSection(topology, definitions, component, retainedInteriors, maxDistanceMeters, newId)
    }

    private fun components(topology: TopologyState, definitions: SimpleSignalsWorldDefinitions,
        maxDistanceMeters: Double, cut: ProtectionBoundaryCut? = null): SectionBoundaryComponents {
        val blockCuts = definitions.fixedBlocks.flatMap { block -> blockEnds(block).mapIndexed { index, end ->
            ProtectionBoundaryCut("${block.blockId}/boundary-$index", end.cutPosition())
        } }
        return SectionBoundaryComponents(topology, listOfNotNull(cut) + markers(definitions) + blockCuts, maxDistanceMeters)
    }

    private fun occupied(definitions: SimpleSignalsWorldDefinitions, component: SectionBoundaryComponent,
        retainedInteriors: Map<String, List<ProtectionRegionInteriorSlice>>): Boolean =
        definitions.fixedBlocks.any { block -> overlaps(component.interior, block.traversals.map {
            ProtectionRegionInteriorSlice(it.railId, it.lowerAbMeters, it.upperAbMeters) }) } ||
            definitions.regions.any { it.boundaryDraft == null && overlaps(component.interior, retainedInteriors[it.regionId].orEmpty()) }

    private fun completeSection(topology: TopologyState, definitions: SimpleSignalsWorldDefinitions,
        component: SectionBoundaryComponent, retainedInteriors: Map<String, List<ProtectionRegionInteriorSlice>>,
        maxDistanceMeters: Double, newId: () -> String): SectionAuthoringEdit {
        val owners = definitions.regions.filter { overlaps(component.interior, retainedInteriors[it.regionId].orEmpty()) }
        require(owners.size <= 1) { "These cuts would merge established protection regions" }
        val owner = owners.singleOrNull()
        val found = component.boundaries
        val pending = definitions.pendingProtectionBoundaries.filterNot { pending -> found.any { samePoint(it.position, pending.position) } }
        if (owner == null && component.blockPath != null) {
            val block = BlockPathBuilder.definition(newId(), component.blockPath)
            val made = createBlock(definitions, block)
            return SectionAuthoringEdit(SimpleSignalsDefinitionPatch(upsertFixedBlocks = listOf(block), pendingProtectionBoundaries = pending), made.text)
        }
        require(owner == null || owner.effectiveCuts.all { remaining -> found.any { samePoint(it.position, remaining.position) } }) {
            "The closed component does not include every remaining boundary of the existing region"
        }
        require(owner != null || containsConflict(topology, component.interior)) {
            "A turnout-free section requires two distinct boundaries"
        }
        val established = definitions.regions.flatMap { it.effectiveCuts }.map { it.boundaryId }.toSet()
        val blockBorderIds = definitions.fixedBlocks.flatMap { block -> blockEnds(block).indices.map { "${block.blockId}/boundary-$it" } }.toSet()
        val regionBoundaries = found.map { boundary ->
            val accepted = (owner?.boundaries.orEmpty().map { it.cut() } + owner?.effectiveCuts.orEmpty())
                .firstOrNull { samePoint(it.position, boundary.position) }
            when {
                accepted != null -> boundary.copy(boundaryId = accepted.boundaryId)
                boundary.boundaryId in established || boundary.boundaryId in blockBorderIds -> boundary.copy(boundaryId = newId())
                else -> boundary
            }
        }
        // Plain paths repaired as an existing protection region still obey its formal endpoint/range rules.
        val verified = ProtectionBoundarySearch.search(topology, regionBoundaries.first(), regionBoundaries,
            maxDistanceMeters)
        require(verified.complete) { verified.detail }
        val region = ProtectionRegionDefinition(owner?.regionId ?: newId(), (owner?.revision ?: -1) + 1, regionBoundaries)
        return SectionAuthoringEdit(SimpleSignalsDefinitionPatch(upsertRegions = listOf(region),
            pendingProtectionBoundaries = pending),
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
