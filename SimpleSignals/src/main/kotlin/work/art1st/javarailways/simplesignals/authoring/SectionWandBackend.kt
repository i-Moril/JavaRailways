package work.art1st.javarailways.simplesignals.authoring

import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceService
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
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
internal data class SectionWandOutcome(val text: WandText, val kind: SectionWandKind = SectionWandKind.PENDING,
    val warnings: List<ProtectionBoundaryDefinition> = emptyList(),
    val warningCapture: SectionWandCapture? = null)

/** Immutable capture and worker-only edit boundary, kept separate from player/Bukkit operations. */
internal interface SectionWandBackend {
    fun capture(worldId: UUID): SectionWandCapture?
    fun current(capture: SectionWandCapture): Boolean
    fun perform(capture: SectionWandCapture, tool: SectionWandTool, leftClick: Boolean,
        point: RailPositionSnapshot, active: () -> Boolean): SectionWandOutcome
}

internal class RuntimeSectionWandBackend(
    private val manager: SimpleSignalsWorldRuntimeManager,
    private val infrastructure: InfrastructureResourceService,
    private val maxRegionDistanceMeters: Double
) : SectionWandBackend {
    private data class Capture(override val worldId: UUID, val runtime: SimpleSignalsWorldRuntime,
        val publication: SimpleSignalsRuntimePublication, val topology: TopologyState) : SectionWandCapture

    override fun capture(worldId: UUID): SectionWandCapture? = manager.authoringRuntime(worldId)?.let {
        Capture(worldId, it, it.authoringPublication(), infrastructure.currentTopology(worldId))
    }

    override fun current(capture: SectionWandCapture): Boolean {
        val captured = capture as Capture
        return manager.authoringRuntime(captured.worldId) === captured.runtime &&
            captured.runtime.authoringPublication() === captured.publication &&
            infrastructure.currentTopology(captured.worldId) === captured.topology
    }

    override fun perform(capture: SectionWandCapture, tool: SectionWandTool, leftClick: Boolean,
        point: RailPositionSnapshot, active: () -> Boolean): SectionWandOutcome {
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
            val region = publication.regions.compilationSnapshot()!!.regionIdsByRail[point.railId].orEmpty()
                .mapNotNull { id -> definitions.regions.singleOrNull { it.regionId == id } }
                .singleOrNull { it.effectiveBoundaries.any { boundary -> boundary.insideFacing == point.facing &&
                    samePoint(boundary.position, point, SectionAuthoringActions.SNAP_METERS) } }
            if (region != null) {
                val found = discover(region)
                return SectionWandOutcome(WandText.key("region.warnings", found.routes.size, found.warningBoundaryIds.size), SectionWandKind.REGION,
                    warnings = region.boundaries.filter { it.boundaryId in found.warningBoundaryIds }, warningCapture = captured)
            }
        }
        val edit = when (tool) {
            SectionWandTool.DELETE -> SectionAuthoringActions.deleteSection(definitions, point, leftClick, topology)
            SectionWandTool.CREATE -> {
                val placed = SectionAuthoringActions.placeSectionBoundary(topology, definitions, point,
                    maxRegionDistanceMeters, publication.regions.compilationSnapshot()!!.regionsById.mapValues { it.value.interior },
                    { rails -> infrastructure.operatingDirections(captured.worldId, topology, rails).directions })
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
            SectionWandOutcome(WandText.key("section.applied", edit.text, result.definitions!!.definitionRevision),
                kind,
                warningBoundaries.takeIf { retain }.orEmpty(), next.takeIf { retain && warningBoundaries.isNotEmpty() })
        } else SectionWandOutcome(WandText.diagnostic(SimpleSignalsAdminCommand.formatResult(result)))
    }
}
