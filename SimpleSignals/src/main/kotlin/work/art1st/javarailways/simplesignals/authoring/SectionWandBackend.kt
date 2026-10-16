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
    CREATE("create"), DELETE("delete")
}

internal enum class SectionWandKind { PENDING, BLOCK, REGION }
internal interface SectionWandCapture { val worldId: UUID }
internal interface SectionWandDeletion {
    val text: WandText
    val retainDevices: Boolean
}
internal data class SectionWandOutcome(val text: WandText, val kind: SectionWandKind = SectionWandKind.PENDING,
    val warnings: List<ProtectionBoundaryDefinition> = emptyList(),
    val warningCapture: SectionWandCapture? = null)

/** Immutable capture and worker-only edit boundary, kept separate from player/Bukkit operations. */
internal interface SectionWandBackend {
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
    private val maxRegionDistanceMeters: Double
) : SectionWandBackend {
    private data class Capture(override val worldId: UUID, val runtime: SimpleSignalsWorldRuntime,
        val publication: SimpleSignalsRuntimePublication, val topology: TopologyState) : SectionWandCapture
    private data class Deletion(val capture: Capture, val edit: SectionAuthoringEdit, val kind: SectionWandKind,
        override val text: WandText, override val retainDevices: Boolean) : SectionWandDeletion

    override fun capture(worldId: UUID): SectionWandCapture? = manager.authoringRuntime(worldId)?.let {
        Capture(worldId, it, it.authoringPublication(), infrastructure.currentTopology(worldId))
    }

    override fun current(capture: SectionWandCapture): Boolean {
        val captured = capture as Capture
        return manager.authoringRuntime(captured.worldId) === captured.runtime &&
            captured.runtime.authoringPublication() === captured.publication &&
            infrastructure.currentTopology(captured.worldId) === captured.topology
    }

    override fun prepareDeletion(capture: SectionWandCapture, point: RailCutPosition, retainDevices: Boolean): SectionWandDeletion? {
        require(current(capture)) { "Selection expired; select the rail again" }
        val captured = capture as Capture
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

    override fun perform(capture: SectionWandCapture, tool: SectionWandTool, leftClick: Boolean,
        point: RailCutPosition, retainDevices: Boolean, active: () -> Boolean): SectionWandOutcome {
        require(active() && current(capture)) { "Selection expired; select the rail again" }
        val captured = capture as Capture
        val publication = captured.publication
        val definitions = publication.definitions
        val topology = captured.topology
        var warningBoundaries = emptyList<ProtectionBoundaryDefinition>()
        fun discover(region: ProtectionRegionDefinition): DiscoveredProtectionRoutes {
            val resolved = ProtectionRegionGraphResolver.resolve(region, ProtectionRegionTopology.direct(topology))
            require(resolved is ProtectionRegionGraphResolution.Resolved) { "Protection region is unavailable" }
            val interior = resolved.region.interior
            val directions = infrastructure.operatingDirections(captured.worldId, topology, interior.map { it.railId }.toSet())
            return ProtectionRouteDiscovery.discover(topology, region, interior, directions.directions)
        }
        if (tool == SectionWandTool.CREATE && leftClick) {
            val compiled = publication.regions.compilationSnapshot()!!
            val nearby = compiled.regionIdsByRail[point.railId].orEmpty()
                .mapNotNull { id -> definitions.regions.singleOrNull { it.regionId == id } }
                .filter { it.boundaryDraft == null }
            val inside = nearby.filter { region -> compiled.regionsById[region.regionId]!!.interior.any { slice ->
                slice.railId == point.railId && point.arcLengthInAbMeters > slice.startAbMeters && point.arcLengthInAbMeters < slice.endAbMeters } }
            val candidates = inside.ifEmpty { nearby.filter { region ->
                region.effectiveCuts.any { samePoint(it.position, point) } } }
            if (candidates.size > 1) return SectionWandOutcome(WandText.key("section.select.interior"))
            val region = candidates.singleOrNull()
            if (region != null) {
                val found = discover(region)
                return SectionWandOutcome(WandText.key("region.warnings", found.routes.size, found.warningBoundaryIds.size), SectionWandKind.REGION,
                    warnings = region.boundaries.filter { it.boundaryId in found.warningBoundaryIds }, warningCapture = captured)
            }
        }
        val edit = when (tool) {
            SectionWandTool.DELETE -> SectionAuthoringActions.deleteSection(definitions, point, leftClick, topology, retainDevices)
            SectionWandTool.CREATE -> {
                val interiors = publication.regions.compilationSnapshot()!!.regionsById.mapValues { it.value.interior }
                val directions = { rails: Set<UUID> -> infrastructure.operatingDirections(captured.worldId, topology, rails).directions }
                val placed = if (leftClick) SectionAuthoringActions.confirmSection(topology, definitions, point,
                    maxRegionDistanceMeters, interiors, directions)
                else SectionAuthoringActions.placeSectionBoundary(topology, definitions, point,
                    maxRegionDistanceMeters, interiors, directions)
                val region = placed.patch.upsertRegions.singleOrNull()?.takeIf { it.boundaryDraft == null &&
                    definitions.regions.none { old -> old.regionId == it.regionId } }
                if (region == null) placed else {
                    val found = discover(region)
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
