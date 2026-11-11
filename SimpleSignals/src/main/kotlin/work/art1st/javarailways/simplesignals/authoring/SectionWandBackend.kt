package work.art1st.javarailways.simplesignals.authoring

import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceService
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.simplesignals.command.SimpleSignalsAdminCommand
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsDefinitionAuthoringStatus
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsDefinitionPatch
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsRuntimePublication
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsWorldRuntime
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsWorldRuntimeManager
import work.art1st.javarailways.simplesignals.region.*
import java.util.UUID

internal enum class SectionWandTool(val key: String) {
    CREATE("create"), NAME("name"), DELETE("delete")
}

internal enum class SectionWandKind { PENDING, BLOCK, REGION }
internal interface SectionWandCapture { val worldId: UUID }
internal interface SectionWandDeletion {
    val text: WandText
    val retainDevices: Boolean
}
internal interface SectionWandRepair { val text: WandText }
internal interface SectionBoundaryNaming { val initial: String; val description: WandText }
internal data class SectionWandOutcome(val text: WandText, val kind: SectionWandKind = SectionWandKind.PENDING,
    val warnings: List<ProtectionBoundaryDefinition> = emptyList(),
    val warningCapture: SectionWandCapture? = null)

/** Immutable capture and worker-only edit boundary, kept separate from player/Bukkit operations. */
internal interface SectionWandBackend {
    fun prepareRepair(capture: SectionWandCapture, point: RailCutPosition): SectionWandRepair? = null
    fun repair(selection: SectionWandRepair, active: () -> Boolean): SectionWandOutcome = error("Repair unavailable")
    fun naming(capture: SectionWandCapture, point: RailCutPosition): SectionBoundaryNaming? = null
    fun rename(selection: SectionBoundaryNaming, name: String, active: () -> Boolean): SectionWandOutcome = error("Naming unavailable")
    fun capture(worldId: UUID): SectionWandCapture?
    fun current(capture: SectionWandCapture): Boolean
    fun perform(capture: SectionWandCapture, tool: SectionWandTool, leftClick: Boolean,
        point: RailCutPosition, retainDevices: Boolean, active: () -> Boolean): SectionWandOutcome
    fun prepareDeletion(capture: SectionWandCapture, point: RailCutPosition, retainDevices: Boolean): SectionWandDeletion?
    fun delete(selection: SectionWandDeletion, active: () -> Boolean): SectionWandOutcome
}

internal class RuntimeSectionWandBackend(
    private val manager: SimpleSignalsWorldRuntimeManager,
    private val infrastructure: InfrastructureResourceService,
    private val maxRegionDistanceMeters: Double,
    private val targets: work.art1st.javarailways.bukkit.api.rail.RailTargetService? = null
) : SectionWandBackend {
    private data class Capture(override val worldId: UUID, val runtime: SimpleSignalsWorldRuntime,
        val publication: SimpleSignalsRuntimePublication, val topology: TopologyState) : SectionWandCapture {
        val watchedRails: MutableSet<UUID> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        fun watch(railId: UUID) {
            watchedRails += railId
            publication.topologyIndex?.railsByObject?.values?.filter { railId in it }?.forEach(watchedRails::addAll)
        }
    }
    private data class Deletion(val capture: Capture, val edit: SectionAuthoringEdit, val kind: SectionWandKind,
        override val text: WandText, override val retainDevices: Boolean) : SectionWandDeletion

    private data class Repair(val capture: Capture, val edit: SectionAuthoringEdit) : SectionWandRepair {
        override val text: WandText get() = edit.text
    }

    private data class Naming(val capture: Capture, val position: RailCutPosition,
        override val initial: String, override val description: WandText) : SectionBoundaryNaming

    override fun naming(capture: SectionWandCapture, point: RailCutPosition): SectionBoundaryNaming? {
        val captured = capture as Capture
        captured.watch(point.railId)
        require(current(capture)) { "Selection expired; select again" }
        val names = BoundaryNames(captured.publication.definitions, captured.topology) { targets?.findCurve(captured.worldId, it)?.orElse(null) }
        val selected = names.nearest(point) ?: return null
        val values = names.values(selected)
        val description = if (values.size > 1) WandText.key("boundary.conflict_detail", names.label(selected), values.sorted().joinToString(" / "))
            else names.label(selected)
        return Naming(captured, selected, values.singleOrNull().orEmpty(), description)
    }

    override fun rename(selection: SectionBoundaryNaming, name: String, active: () -> Boolean): SectionWandOutcome {
        val selected = selection as Naming
        val captured = selected.capture
        require(active() && current(captured)) { "Selection expired; select again" }
        val replacement = BoundaryNames(captured.publication.definitions, captured.topology).rename(selected.position, name)
        val result = manager.applyDefinitionPatch(captured.worldId, captured.publication.definitions.definitionRevision,
            SimpleSignalsDefinitionPatch(boundaryNames = replacement), captured.runtime, captured.topology, active)
        return SectionWandOutcome(if (result.status == SimpleSignalsDefinitionAuthoringStatus.APPLIED)
            WandText.key("boundary.saved") else WandText.diagnostic(SimpleSignalsAdminCommand.formatResult(result)))
    }

    override fun capture(worldId: UUID): SectionWandCapture? = manager.authoringRuntime(worldId)?.let {
        it.authoringPublication().let { publication -> Capture(worldId, it, publication, publication.topology) }
    }

    override fun current(capture: SectionWandCapture): Boolean {
        val captured = capture as Capture
        return manager.authoringRuntime(captured.worldId) === captured.runtime &&
            captured.runtime.authoringPublication().definitions == captured.publication.definitions &&
            infrastructure.currentTopology(captured.worldId).preservesRails(captured.topology, captured.watchedRails)
    }

    override fun prepareDeletion(capture: SectionWandCapture, point: RailCutPosition, retainDevices: Boolean): SectionWandDeletion? {
        val captured = capture as Capture
        captured.watch(point.railId)
        require(current(capture)) { "Selection expired; select the rail again" }
        val definitions = captured.publication.definitions
        val blocks = captured.publication.fixedBlocks.snapshot(captured.topology).candidates(setOf(point.railId)).filter { block ->
            block.definition.traversals.any { it.railId == point.railId && point.arcLengthInAbMeters > it.lowerAbMeters &&
                point.arcLengthInAbMeters < it.upperAbMeters } }
        val regions = captured.publication.regions.compilationSnapshot()!!
        val candidates = regions.regionIdsByRail[point.railId].orEmpty().mapNotNull(regions.regionsById::get)
            .filter { region -> region.interior.any { it.railId == point.railId && point.arcLengthInAbMeters > it.startAbMeters &&
                point.arcLengthInAbMeters < it.endAbMeters } }
        require(blocks.size + candidates.size <= 1) { "Several sections overlap here; the deletion target is ambiguous" }
        if (blocks.isNotEmpty()) {
            val block = blocks.single().definition
            return Deletion(captured, SectionAuthoringActions.deleteBlock(definitions, point, false, captured.topology, retainDevices),
                SectionWandKind.BLOCK, authoringName(block.blockId, "name.block"), retainDevices)
        }
        val region = candidates.singleOrNull()?.definition ?: return null
        // Existing cut gestures remain boundary repair; body deletion is a separate confirmed intent.
        if (region.effectiveCuts.any { samePoint(it.position, point, SectionAuthoringActions.SNAP_METERS) }) return null
        return Deletion(captured, SectionAuthoringActions.deleteRegion(definitions, region, retainDevices), SectionWandKind.REGION,
            authoringName(region.regionId, "name.region"), retainDevices)
    }

    override fun delete(selection: SectionWandDeletion, active: () -> Boolean): SectionWandOutcome {
        val selected = selection as Deletion
        val captured = selected.capture
        require(active() && current(captured)) { "Selection expired before submission" }
        val result = manager.applyDefinitionPatch(captured.worldId, captured.publication.definitions.definitionRevision,
            selected.edit.patch, captured.runtime, captured.topology, active)
        return if (result.status == SimpleSignalsDefinitionAuthoringStatus.APPLIED)
            SectionWandOutcome(WandText.key("section.applied", WandText.key("section.deleted", selected.edit.text,
                WandText.key(if (selected.retainDevices) "section.retain.on" else "section.retain.off")), result.definitions!!.definitionRevision), selected.kind)
        else SectionWandOutcome(WandText.diagnostic(SimpleSignalsAdminCommand.formatResult(result)))
    }

    private fun selectedRegion(captured: Capture, point: RailCutPosition): ProtectionRegionDefinition? {
        val compiled = checkNotNull(captured.publication.regions.compilationSnapshot())
        val definitions = captured.publication.definitions
        val nearby = compiled.regionIdsByRail[point.railId].orEmpty()
            .mapNotNull { id -> definitions.regions.singleOrNull { it.regionId == id } }
            .filter { it.boundaryDraft == null }
        val inside = nearby.filter { region -> compiled.regionsById.getValue(region.regionId).interior.any { slice ->
            slice.railId == point.railId && point.arcLengthInAbMeters > slice.startAbMeters && point.arcLengthInAbMeters < slice.endAbMeters } }
        val candidates = inside.ifEmpty { nearby.filter { region -> region.effectiveCuts.any { samePoint(it.position, point) } } }
        require(candidates.size <= 1) { "Several regions overlap here; select the interior" }
        return candidates.singleOrNull()
    }

    private fun discover(captured: Capture, region: ProtectionRegionDefinition): Pair<ResolvedProtectionRegion, DiscoveredProtectionRoutes> {
        val resolved = ProtectionRegionGraphResolver.resolve(region, ProtectionRegionTopology.direct(captured.topology))
        require(resolved is ProtectionRegionGraphResolution.Resolved) { "Protection region geometry is unavailable; repair was not applied" }
        val interior = resolved.region.interior
        interior.forEach { captured.watch(it.railId) }
        val directions = infrastructure.operatingDirections(captured.worldId, captured.topology, interior.map { it.railId }.toSet())
        return resolved.region to ProtectionRouteDiscovery.discover(captured.topology, region, interior, directions.directions)
    }

    override fun prepareRepair(capture: SectionWandCapture, point: RailCutPosition): SectionWandRepair? {
        val captured = capture as Capture
        captured.watch(point.railId)
        require(current(captured)) { "Selection expired; select the rail again" }
        val region = selectedRegion(captured, point) ?: return null
        val publication = captured.publication
        val compiled = checkNotNull(publication.regions.compilationSnapshot())
        val hasTable = publication.definitions.routeTables.any { it.regionId == region.regionId }
        if (region.regionId !in compiled.unavailableRegionIds && hasTable &&
            region.regionId !in publication.routeTables.unavailableRegionIds()) return null
        val (resolved, found) = discover(captured, region)
        val edit = ProtectionRegionRepair.prepare(captured.topology, publication.definitions, resolved, found)
        return Repair(captured, edit)
    }

    override fun repair(selection: SectionWandRepair, active: () -> Boolean): SectionWandOutcome {
        val selected = selection as Repair
        val captured = selected.capture
        require(active() && current(captured)) { "Selection expired before submission" }
        val result = manager.applyDefinitionPatch(captured.worldId, captured.publication.definitions.definitionRevision,
            selected.edit.patch, captured.runtime, captured.topology, active)
        return if (result.status == SimpleSignalsDefinitionAuthoringStatus.APPLIED)
            SectionWandOutcome(WandText.key("section.applied", WandText.key("region.repair.completed", selected.edit.text),
                result.definitions!!.definitionRevision), SectionWandKind.REGION)
        else SectionWandOutcome(WandText.diagnostic(SimpleSignalsAdminCommand.formatResult(result)))
    }

    override fun perform(capture: SectionWandCapture, tool: SectionWandTool, leftClick: Boolean,
        point: RailCutPosition, retainDevices: Boolean, active: () -> Boolean): SectionWandOutcome {
        val captured = capture as Capture
        captured.watch(point.railId)
        require(active() && current(capture)) { "Selection expired; select the rail again" }
        val publication = captured.publication
        val definitions = publication.definitions
        val topology = captured.topology
        var warningBoundaries = emptyList<ProtectionBoundaryDefinition>()
        if (tool == SectionWandTool.CREATE && leftClick) {
            val region = selectedRegion(captured, point)
            if (region != null) {
                val (_, found) = discover(captured, region)
                return SectionWandOutcome(WandText.key("region.warnings", found.routes.size, found.warningBoundaryIds.size), SectionWandKind.REGION,
                    warnings = region.boundaries.filter { it.boundaryId in found.warningBoundaryIds }, warningCapture = captured)
            }
        }
        val edit = when (tool) {
            SectionWandTool.NAME -> error("Naming requires a boundary selection")
            SectionWandTool.DELETE -> SectionAuthoringActions.deleteSection(definitions, point, leftClick, topology, retainDevices)
            SectionWandTool.CREATE -> {
                val interiors = publication.regions.compilationSnapshot()!!.regionsById.mapValues { it.value.interior }
                val placed = if (leftClick) SectionAuthoringActions.confirmSection(topology, definitions, point,
                    maxRegionDistanceMeters, interiors)
                else SectionAuthoringActions.placeSectionBoundary(topology, definitions, point,
                    maxRegionDistanceMeters, interiors)
                val region = placed.patch.upsertRegions.singleOrNull()?.takeIf { it.boundaryDraft == null }
                if (region == null) placed else if (definitions.regions.any { it.regionId == region.regionId }) {
                    val (resolved, found) = discover(captured, region)
                    val repaired = ProtectionRegionRepair.prepare(topology, definitions, resolved, found, placed.patch.pendingProtectionBoundaries)
                    SectionAuthoringEdit(repaired.patch,
                        WandText.key("region.repair.completed", repaired.text))
                } else {
                    val (_, found) = discover(captured, region)
                    require(found.routes.isNotEmpty()) { "No direction-legal entry/exit route; protection region was not created" }
                    warningBoundaries = region.boundaries.filter { it.boundaryId in found.warningBoundaryIds }
                    val table = ProtectionRouteTableDefinition(ProtectionRouteDiscovery.stableId(region.regionId, "table"),
                        0, region.regionId, region.revision, found.routes)
                    SectionAuthoringEdit(SimpleSignalsDefinitionPatch(upsertRegions = placed.patch.upsertRegions,
                        upsertRouteTables = listOf(table), pendingProtectionBoundaries = placed.patch.pendingProtectionBoundaries),
                        WandText.key("region.routes.generated", placed.text, found.routes.size, warningBoundaries.size))
                }
            }
        }
        if (edit.patch.upsertRegions.isEmpty() && edit.patch.upsertFixedBlocks.isEmpty() &&
            edit.patch.removeFixedBlockIds.isEmpty() && edit.patch.pendingProtectionBoundaries == null)
            return SectionWandOutcome(edit.text)
        require(active() && current(capture)) { "Selection expired before submission" }
        val result = manager.applyDefinitionPatch(captured.worldId, definitions.definitionRevision, edit.patch,
            captured.runtime, topology, active)
        return if (result.status == SimpleSignalsDefinitionAuthoringStatus.APPLIED) {
            val next = if (warningBoundaries.isNotEmpty()) capture(captured.worldId) as? Capture else null
            val retain = next?.publication?.definitions === result.definitions
            val kind = when {
                edit.patch.upsertFixedBlocks.isNotEmpty() || edit.patch.removeFixedBlockIds.isNotEmpty() -> SectionWandKind.BLOCK
                edit.patch.upsertRegions.isNotEmpty() -> SectionWandKind.REGION
                else -> SectionWandKind.PENDING
            }
            val text = if (edit.patch.removeFixedBlockIds.isNotEmpty()) WandText.key("section.deleted", edit.text,
                WandText.key(if (retainDevices) "section.retain.on" else "section.retain.off")) else edit.text
            SectionWandOutcome(WandText.key("section.applied", text, result.definitions!!.definitionRevision),
                kind,
                warningBoundaries.takeIf { retain }.orEmpty(), next.takeIf { retain && warningBoundaries.isNotEmpty() })
        } else SectionWandOutcome(WandText.diagnostic(SimpleSignalsAdminCommand.formatResult(result)))
    }
}
