package work.art1st.javarailways.simplesignals.authoring

import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceService
import work.art1st.javarailways.core.api.threading.ThreadGuard
import work.art1st.javarailways.simplesignals.command.SimpleSignalsAdminCommand
import work.art1st.javarailways.simplesignals.region.ProtectionRouteDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionRouteTableDefinition
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsDefinitionAuthoringStatus
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsDefinitionPatch
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsWorldRuntimeManager
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsRuntimePublication
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import java.util.UUID

internal class RecordedRouteDeletion(val selection: RoutePreviewSelection,
    val publication: SimpleSignalsRuntimePublication, val topology: TopologyState,
    val table: ProtectionRouteTableDefinition, val route: ProtectionRouteDefinition)

/** Records displayed paths or deletes authored routes on a worker through the existing definition transaction. */
internal class CurrentRouteRecording(private val manager: SimpleSignalsWorldRuntimeManager,
    private val infrastructure: InfrastructureResourceService, private val preview: RoutePreviewBackend) {
    fun deletionChoices(selection: RoutePreviewSelection): List<RecordedRouteDeletion> {
        ThreadGuard.ensureOffMainThread("SimpleSignals route deletion selection")
        val runtime = selection.runtime
        require(manager.authoringRuntime(selection.worldId) === runtime && !runtime.closed) {
            "Selected world runtime was unloaded; select again"
        }
        val publication = runtime.authoringPublication()
        require(publication.definitions.regions.any { region -> region.regionId == selection.regionId &&
            selection.entry in region.boundaries }) { "Selected region entry changed; select again" }
        val topology = infrastructure.currentTopology(selection.worldId)
        val table = publication.definitions.routeTables.singleOrNull { it.regionId == selection.regionId }
            ?: return emptyList()
        return table.routes.sortedBy { it.routeId }.map { RecordedRouteDeletion(selection, publication, topology, table, it) }
    }

    fun delete(choice: RecordedRouteDeletion, active: () -> Boolean): WandText {
        ThreadGuard.ensureOffMainThread("SimpleSignals route deletion")
        val selection = choice.selection
        require(active() && manager.authoringRuntime(selection.worldId) === selection.runtime &&
            !selection.runtime.closed && selection.runtime.authoringPublication() === choice.publication &&
            infrastructure.currentTopology(selection.worldId) === choice.topology) {
            "Route deletion selection expired; select again"
        }
        require(choice.table in choice.publication.definitions.routeTables && choice.route in choice.table.routes &&
            choice.table.regionId == selection.regionId) { "The selected authored route no longer exists" }
        val updated = ProtectionRouteTableDefinition(choice.table.tableId, Math.addExact(choice.table.revision, 1L),
            choice.table.regionId, choice.table.regionRevision, choice.table.routes.filterNot { it.routeId == choice.route.routeId })
        val result = manager.applyDefinitionPatch(selection.worldId, choice.publication.definitions.definitionRevision,
            SimpleSignalsDefinitionPatch(upsertRouteTables = listOf(updated)), selection.runtime, choice.topology, active)
        return if (result.status == SimpleSignalsDefinitionAuthoringStatus.APPLIED)
            WandText.key("route.deleted", authoringName(choice.route.routeId, "name.route"), result.definitions!!.definitionRevision)
        else WandText.diagnostic(SimpleSignalsAdminCommand.formatResult(result))
    }

    fun record(selection: RoutePreviewSelection, value: RoutePreviewComputation, active: () -> Boolean): WandText {
        ThreadGuard.ensureOffMainThread("SimpleSignals current route recording")
        require(active()) { "Route selection is no longer active" }
        val runtime = selection.runtime
        require(manager.authoringRuntime(selection.worldId) === runtime && !runtime.closed) {
            "Selected world runtime was unloaded; select again"
        }
        val publication = runtime.authoringPublication()
        val topology = infrastructure.currentTopology(selection.worldId)
        require(preview.current(selection, value)) { "Route preview expired; wait for the current path" }
        val region = checkNotNull(value.regionState.definition) { "Selected protection region is missing" }
        val path = value.path
        require(!value.regionState.unavailable && region.boundaryDraft == null &&
            region.regionId == selection.regionId && publication.definitions.regions.any { it == region } &&
            region.boundaries.contains(selection.entry) && region.boundaries.contains(selection.exit) &&
            path.entry == selection.entry && path.requestedExit == selection.exit &&
            path.end == CurrentRouteEnd.REACHED_EXIT && !path.waitingForObservation) {
            "A current complete preview reaching the selected exit is required"
        }
        require(active() && runtime.authoringPublication() === publication) { "Route definitions changed; select again" }
        // The path is immutable. From here on live turnout selections are deliberately not a commit guard.
        val definitions = publication.definitions
        val table = definitions.routeTables.singleOrNull { it.regionId == region.regionId }
        val paired = table?.routes.orEmpty().filter {
            it.entryBoundaryId == path.entry.boundaryId && it.exitBoundaryId == path.requestedExit.boundaryId }
        val duplicate = paired.firstOrNull { it.traversals == path.traversals }
        if (duplicate != null) return WandText.key("route.unchanged", authoringName(region.regionId, "name.region"), authoringName(duplicate.routeId, "name.route"))
        require(paired.size <= 1) { "This boundary pair has several authored routes; use the definition JSON editor to choose which route to replace" }
        val previous = paired.singleOrNull()
        val route = ProtectionRouteDefinition(previous?.routeId ?: "simplesignals:${UUID.randomUUID()}", path.entry.boundaryId,
            path.requestedExit.boundaryId, path.traversals)
        val updated = ProtectionRouteTableDefinition(table?.tableId ?: "simplesignals:${UUID.randomUUID()}",
            table?.let { Math.addExact(it.revision, 1L) } ?: 0L, region.regionId, region.revision,
            if (previous == null) table?.routes.orEmpty() + route else table!!.routes.map { if (it === previous) route else it })
        val result = manager.applyDefinitionPatch(selection.worldId, definitions.definitionRevision,
            SimpleSignalsDefinitionPatch(upsertRouteTables = listOf(updated)), runtime, topology, active)
        return if (result.status == SimpleSignalsDefinitionAuthoringStatus.APPLIED)
            WandText.key("route.recorded", authoringName(region.regionId, "name.region"), authoringName(route.routeId, "name.route"), result.definitions!!.definitionRevision)
        else WandText.diagnostic(SimpleSignalsAdminCommand.formatResult(result))
    }
}
