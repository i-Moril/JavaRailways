package work.art1st.javarailways.simplesignals.authoring

import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitions
import work.art1st.javarailways.simplesignals.region.*
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsDefinitionPatch
import work.art1st.javarailways.simplesignals.signal.ProtectionSignalKind
import work.art1st.javarailways.simplesignals.signal.RouteIndicatorDefinition

/** Worker-only repair preparation. Route validity belongs to the canonical table compiler. */
internal object ProtectionRegionRepair {
    fun prepare(topology: TopologyState, definitions: SimpleSignalsWorldDefinitions,
        resolved: ResolvedProtectionRegion, found: DiscoveredProtectionRoutes,
        pendingBoundaries: List<ProtectionBoundaryCut>? = null): SectionAuthoringEdit {
        val region = resolved.definition
        require(region.boundaryDraft == null) { "Protection region geometry is unavailable; repair was not applied" }
        val old = definitions.routeTables.singleOrNull { it.regionId == region.regionId }
        val catalog = ProtectionRouteTableCatalog(ProtectionRegionCatalog.fromCompiled(topology,
            definitions.definitionRevision, listOf(resolved), emptySet()))
        val validation = catalog.publish(definitions.definitionRevision,
            listOfNotNull(old?.let { ProtectionRouteTableDefinition(it.tableId, it.revision, it.regionId, region.revision, it.routes) }),
            revalidateRoutes = true)
        require(validation.status != ProtectionRouteTableCatalogStatus.REJECTED) { validation.diagnostics.joinToString { it.detail } }
        val retained = catalog.usableDefinitions().singleOrNull()?.routes.orEmpty()
        val pairs = retained.mapTo(hashSetOf()) { it.entryBoundaryId to it.exitBoundaryId }
        // A removed identity must never acquire a different path and silently rebind a device.
        val usedIds = definitions.routeTables.flatMap { it.routes }.mapTo(hashSetOf()) { it.routeId }
        val generated = found.routes.filter { (it.entryBoundaryId to it.exitBoundaryId) !in pairs }.map { route ->
            var id = route.routeId
            var attempt = 0
            while (!usedIds.add(id)) {
                id = ProtectionRouteDiscovery.stableId(region.regionId, "repair", region.revision.toString(),
                    definitions.definitionRevision.toString(), route.routeId, (attempt++).toString())
            }
            ProtectionRouteDefinition(id, route.entryBoundaryId, route.exitBoundaryId, route.traversals)
        }
        require(retained.isNotEmpty() || generated.isNotEmpty()) {
            "No direction-legal entry/exit route; protection region repair was not applied"
        }
        val table = ProtectionRouteTableDefinition(old?.tableId ?: ProtectionRouteDiscovery.stableId(region.regionId, "table"),
            old?.let { Math.addExact(it.revision, 1L) } ?: 0, region.regionId, region.revision, retained + generated)
        val previous = definitions.regions.single { it.regionId == region.regionId }
        val removedEntries = previous.boundaries.mapTo(hashSetOf()) { it.boundaryId }
        old?.routes?.forEach { removedEntries += it.entryBoundaryId; removedEntries += it.exitBoundaryId }
        removedEntries.removeAll(region.boundaries.map { it.boundaryId }.toSet())
        val removedRouteIds = old?.routes.orEmpty().mapTo(hashSetOf()) { it.routeId }
        removedRouteIds.removeAll(retained.map { it.routeId }.toSet())
        // Removed route bindings/mappings are normalized by the manager in the same transaction.
        val signals = definitions.protectionSignals.filter {
            it.kind == ProtectionSignalKind.CURRENT_ENTRY && it.bindingId in removedEntries
        }.map { it.copy(revision = Math.addExact(it.revision, 1L), bindingId = null) }
        val indicators = definitions.routeIndicators.filter { it.entryBoundaryId in removedEntries }.map {
            RouteIndicatorDefinition(it.indicatorId, Math.addExact(it.revision, 1L), null,
                it.noRouteModelVariant, emptyMap(), it.model)
        }
        val changedMappings = definitions.routeIndicators.count {
            it.entryBoundaryId !in removedEntries && it.modelVariantsByRouteId.keys.any { routeId -> routeId in removedRouteIds }
        }
        val unbound = signals.size + definitions.protectionSignals.count {
            it.kind == ProtectionSignalKind.FIXED_ROUTE && it.bindingId in removedRouteIds
        }
        return SectionAuthoringEdit(SimpleSignalsDefinitionPatch(upsertRegions = listOf(region), upsertRouteTables = listOf(table),
            upsertProtectionSignals = signals, upsertRouteIndicators = indicators, pendingProtectionBoundaries = pendingBoundaries),
            WandText.key("region.repair.summary", retained.size, removedRouteIds.size, generated.size, unbound, indicators.size, changedMappings))
    }
}
