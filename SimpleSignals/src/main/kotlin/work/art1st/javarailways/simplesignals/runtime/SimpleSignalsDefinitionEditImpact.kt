package work.art1st.javarailways.simplesignals.runtime

import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditImpact
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.simplesignals.block.FIXED_BLOCK_RESOURCE_TYPE
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitions
import work.art1st.javarailways.simplesignals.presentation.ModelPlacement
import work.art1st.javarailways.simplesignals.region.ProtectionRegionDefinition
import java.util.UUID
import kotlin.math.abs

/** Worker-only impact of authored changes and their compiled policy consumers in both snapshots. */
internal object SimpleSignalsDefinitionEditImpact {
    fun impact(
        previous: SimpleSignalsRuntimePublication?, candidate: SimpleSignalsRuntimePublication
    ): TopologyEditImpact? {
        val old = previous?.definitions ?: SimpleSignalsWorldDefinitions.empty(candidate.definitions.worldId)
        val next = candidate.definitions
        fun <T> changed(before: List<T>, after: List<T>, id: (T) -> String): Set<String> {
            val beforeById = before.associateBy(id)
            val afterById = after.associateBy(id)
            return (beforeById.keys + afterById.keys).filterTo(linkedSetOf()) {
                beforeById[it] != afterById[it]
            }
        }
        val regions = changed(old.regions, next.regions) { it.regionId }.toMutableSet()
        val tables = changed(old.routeTables, next.routeTables) { it.tableId }.toMutableSet()
        val blocks = changed(old.fixedBlocks, next.fixedBlocks) { it.blockId }.toMutableSet()
        val signals = (changed(old.fixedBlockSignals, next.fixedBlockSignals) { it.signalId } +
            changed(old.protectionSignals, next.protectionSignals) { it.signalId }).toMutableSet()
        val indicators = changed(old.routeIndicators, next.routeIndicators) { it.indicatorId }
        val attached = changed(old.attachedSignals, next.attachedSignals) { it.signalId }
        if (previous != null && previous.topology !== candidate.topology) {
            // Compare disposable compiled values, excluding revision-local IDs and callback identity.
            // An unrelated topology publication alone must not make all definitions edited.
            val oldRegions = checkNotNull(previous.regions.compilationSnapshot()).regionsById
            checkNotNull(candidate.regions.compilationSnapshot()).regionsById.forEach { (id, region) ->
                if (oldRegions[id]?.interior != region.interior) regions += id
            }
            val oldBlocks = previous.fixedBlocks.snapshot(previous.topology).blocksById
            candidate.fixedBlocks.snapshot(candidate.topology).blocksById.forEach { (id, block) ->
                if (oldBlocks[id]?.traversals != block.traversals || oldBlocks[id]?.lengthMeters != block.lengthMeters)
                    blocks += id
            }
            candidate.routeSelection.routesById.forEach { (id, route) ->
                if (previous.routeSelection.routesById[id]?.requiredContinuations != route.requiredContinuations)
                    tables += route.tableId
            }
            val oldBindings = (previous.fixedBlockSignals.bindings + previous.protectionSignals.bindings)
                .associateBy { it.signalId }
            (candidate.fixedBlockSignals.bindings + candidate.protectionSignals.bindings).forEach { binding ->
                val before = oldBindings[binding.signalId]
                if (before == null || before.downstreamSignals != binding.downstreamSignals ||
                    before.observedSections != binding.observedSections ||
                    before.continuationEntries != binding.continuationEntries)
                    signals += binding.signalId
            }
        }
        val result = linkedSetOf<UUID>()
        val changedPending = changed(old.pendingProtectionBoundaries, next.pendingProtectionBoundaries) { it.boundaryId }
        (old.pendingProtectionBoundaries + next.pendingProtectionBoundaries).filter { it.boundaryId in changedPending }
            .mapTo(result) { it.position.railId }
        listOfNotNull(previous, candidate).forEach { publication ->
            collect(publication, regions, tables, blocks, signals, indicators, attached, result)
        }
        // Only published identities exist in the host provider. New blocks are protected through
        // their exact rail impact until publication; updates/deletions also protect the old token.
        val oldBlockIds = old.fixedBlocks.mapTo(hashSetOf()) { it.blockId }
        val resources = blocks.filter { it in oldBlockIds }.map {
            StableInfrastructureResource(FIXED_BLOCK_RESOURCE_TYPE, it)
        }
        return if (result.isEmpty() && resources.isEmpty()) null else
            TopologyEditImpact(result, emptySet(), resources)
    }

    private fun collect(
        publication: SimpleSignalsRuntimePublication,
        changedRegions: Set<String>, changedTables: Set<String>, changedBlocks: Set<String>,
        changedSignals: Set<String>, changedIndicators: Set<String>, changedAttached: Set<String>,
        rails: MutableSet<UUID>
    ) {
        val definitions = publication.definitions
        val blockSnapshot = publication.fixedBlocks.snapshot(publication.topology)
        val regionSnapshot = checkNotNull(publication.regions.compilationSnapshot())
        val sections = linkedSetOf<SectionRef>()
        val entries = linkedSetOf<String>()
        val regions = changedRegions.toMutableSet()
        definitions.routeTables.filter { it.tableId in changedTables }.mapTo(regions) { it.regionId }
        val editedBlocks = blockSnapshot.blocksById.values.filter { it.definition.blockId in changedBlocks }
        editedBlocks.forEach { block ->
            block.traversals.mapTo(rails) { it.railId }
            sections += SectionRef(SectionKind.FIXED_BLOCK, block.definition.blockId)
        }

        // Region cuts are strictly inside a rail. A direct successor starts at that exact directed
        // cut; topology neighbours or a block across a positive gap are not successor candidates.
        val blocksByEntryRail = blockSnapshot.blocksById.values.flatMap { block ->
            block.entryTraversals.map { entry -> block to entry }
        }.groupBy { (_, entry) -> entry.railId }
        fun successors(region: ProtectionRegionDefinition) =
            region.boundaries.flatMap { boundary ->
                blocksByEntryRail[boundary.position.railId].orEmpty().filter { (_, start) ->
                    start.facing != boundary.insideFacing &&
                        abs(start.travelStartAbMeters - boundary.position.arcLengthInAbMeters) <= Curve.TOLERANCE
                }.map { (block, _) -> block }
            }
        definitions.regions.forEach { region ->
            val successors = successors(region)
            if (successors.any { it.definition.blockId in changedBlocks }) regions += region.regionId
            if (region.regionId in regions) {
                region.boundaries.mapTo(rails) { it.position.railId }
                region.effectiveBoundaries.mapTo(rails) { it.position.railId }
                regionSnapshot.regionsById.getValue(region.regionId).interior.mapTo(rails) { it.railId }
                successors.forEach { block -> block.traversals.mapTo(rails) { it.railId } }
            }
        }
        // Unusable authored routes are still edit-protected configuration, even without a live rule binding.
        val authoredRoutes = definitions.routeTables.flatMap { it.routes }.associateBy { it.routeId }
        val authoredByEntry = authoredRoutes.values.groupBy { it.entryBoundaryId }
        val changedRouteIds = definitions.routeTables.filter { it.regionId in regions }.flatMap { it.routes }
            .mapTo(hashSetOf()) { it.routeId }
        definitions.routeTables.filter { it.regionId in regions }.forEach { table -> table.routes.forEach { route ->
            route.traversals.mapTo(rails) { it.railId }
            entries += route.entryBoundaryId
        } }
        publication.routeSelection.routesById.values.filter { it.regionId in regions }.forEach { route ->
            route.definition.traversals.mapTo(rails) { it.railId }
            sections += SectionRef(SectionKind.PROTECTION_ROUTE, route.definition.routeId)
            entries += route.definition.entryBoundaryId
        }

        val modelsBySignal = definitions.fixedBlockSignals.associate { it.signalId to it.model } +
            definitions.protectionSignals.associate { it.signalId to it.model }
        fun collectSections(observed: Collection<SectionRef>) = observed.forEach { section ->
            when (section.kind) {
                SectionKind.FIXED_BLOCK -> blockSnapshot.blocksById.getValue(section.id)
                    .traversals.mapTo(rails) { it.railId }
                SectionKind.PROTECTION_ROUTE -> publication.routeSelection.routesById.getValue(section.id)
                    .definition.traversals.mapTo(rails) { it.railId }
            }
        }
        val affectedBindings = publication.protectionSignals.ruleRuntime
            ?.affectedBindings(sections, changedSignals, entries).orEmpty()
        affectedBindings.forEach { binding ->
            (modelsBySignal[binding.signalId]?.placement as? ModelPlacement.RailAttached)
                ?.let { rails += it.position.railId }
            collectSections(binding.observedSections)
            binding.downstreamSignals.forEach { rails += it.affectedRailIds }
        }

        // Display edits protect their primary's controlled territory without seeding rule consumers.
        // Conversely, a changed logical signal also protects every display consuming its output.
        val affectedSignalIds = affectedBindings.mapTo(hashSetOf()) { it.signalId }.also { it += changedSignals }
        definitions.protectionSignals.filter { signal ->
            signal.bindingId in entries || signal.bindingId in changedRouteIds
        }.mapTo(affectedSignalIds) { it.signalId }
        val authoredDependencies = definitions.fixedBlockSignals.associate { it.signalId to it.downstreamSignals } +
            definitions.protectionSignals.associate { it.signalId to (it.downstreamSignals + it.downstreamSignalsByRouteId.values.flatten()) }
        val consumers = authoredDependencies.flatMap { (consumer, providers) -> providers.map { it.signalId to consumer } }
            .groupBy({ it.first }, { it.second })
        val pending = java.util.ArrayDeque(affectedSignalIds)
        while (pending.isNotEmpty()) consumers[pending.removeFirst()].orEmpty().forEach {
            if (affectedSignalIds.add(it)) pending += it
        }
        val fixedById = definitions.fixedBlockSignals.associateBy { it.signalId }
        val protectionById = definitions.protectionSignals.associateBy { it.signalId }
        val blocksById = definitions.fixedBlocks.associateBy { it.blockId }
        fun collectAuthoredSignal(id: String) {
            (modelsBySignal[id]?.placement as? ModelPlacement.RailAttached)?.let { rails += it.position.railId }
            fixedById[id]?.let { signal ->
                blocksById[signal.controlledBlockId]?.traversals?.mapTo(rails) { it.railId }
            }
            protectionById[id]?.let { signal ->
                (listOfNotNull(authoredRoutes[signal.bindingId]) + authoredByEntry[signal.bindingId].orEmpty())
                    .forEach { it.traversals.mapTo(rails) { traversal -> traversal.railId } }
            }
        }
        affectedSignalIds.forEach(::collectAuthoredSignal)
        val bindingsBySignal = (publication.fixedBlockSignals.bindings + publication.protectionSignals.bindings)
            .associateBy { it.signalId }
        definitions.attachedSignals.forEach { attached ->
            if (attached.signalId in changedAttached || attached.primarySignalId in affectedSignalIds) {
                (attached.model.placement as? ModelPlacement.RailAttached)?.let { rails += it.position.railId }
                if (attached.signalId in changedAttached) {
                    bindingsBySignal[attached.primarySignalId]?.let { collectSections(it.observedSections) }
                    collectAuthoredSignal(attached.primarySignalId)
                }
            }
        }

        // Indicators consume entry selection only. Their edits protect placement and explicit
        // binding geometry, but do not seed the speed-rule dependency graph.
        val routesByEntry = publication.routeSelection.routesById.values.groupBy { it.definition.entryBoundaryId }
        definitions.routeIndicators.filter { it.indicatorId in changedIndicators || it.entryBoundaryId in entries }
            .forEach { indicator ->
                (indicator.model?.placement as? ModelPlacement.RailAttached)
                    ?.let { rails += it.position.railId }
                routesByEntry[indicator.entryBoundaryId].orEmpty()
                    .forEach { it.definition.traversals.mapTo(rails) { traversal -> traversal.railId } }
                authoredByEntry[indicator.entryBoundaryId].orEmpty()
                    .forEach { it.traversals.mapTo(rails) { traversal -> traversal.railId } }
            }
    }
}
