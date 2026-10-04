package work.art1st.javarailways.simplesignals.authoring

import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceService
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.simplesignals.command.SimpleSignalsAdminCommand
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsDefinitionAuthoringStatus
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsRuntimePublication
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsWorldRuntime
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsWorldRuntimeManager
import java.util.UUID

internal enum class SectionWandTool(val key: String) {
    BLOCK_CREATE("block:create"), BLOCK_DELETE("block:delete"),
    REGION_CREATE("region:create"), REGION_DELETE("region:delete")
}

internal interface SectionWandCapture { val worldId: UUID }
internal data class SectionWandOutcome(val message: String, val firstPoint: RailPositionSnapshot? = null,
    val firstCapture: SectionWandCapture? = null)

/** Immutable capture and worker-only edit boundary, kept separate from player/Bukkit operations. */
internal interface SectionWandBackend {
    fun capture(worldId: UUID): SectionWandCapture?
    fun current(capture: SectionWandCapture): Boolean
    fun perform(capture: SectionWandCapture, tool: SectionWandTool, leftClick: Boolean,
        firstPoint: RailPositionSnapshot?, point: RailPositionSnapshot, active: () -> Boolean): SectionWandOutcome
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
        firstPoint: RailPositionSnapshot?, point: RailPositionSnapshot, active: () -> Boolean): SectionWandOutcome {
        require(active() && current(capture)) { "Selection expired; select the rail again" }
        val captured = capture as Capture
        val publication = captured.publication
        val definitions = publication.definitions
        val topology = captured.topology
        var continuation: RailPositionSnapshot? = null
        val edit = when (tool) {
            SectionWandTool.BLOCK_CREATE -> {
                val selected = if (leftClick) SectionAuthoringActions.selectBlockEnd(definitions, point, topology)
                    else SectionAuthoringActions.snap(definitions, point)
                if (firstPoint == null) return SectionWandOutcome("First boundary selected; choose the second point", selected, captured)
                continuation = selected
                val path = BlockPathBuilder.path(topology, firstPoint, selected)
                val directions = infrastructure.operatingDirections(captured.worldId, topology, path.map { it.railId }.toSet())
                SectionAuthoringActions.createBlock(definitions, BlockPathBuilder.definition(
                    "simplesignals:${UUID.randomUUID()}", path, directions.directions))
            }
            SectionWandTool.BLOCK_DELETE -> SectionAuthoringActions.deleteBlock(definitions, point, leftClick, topology)
            SectionWandTool.REGION_DELETE -> SectionAuthoringActions.deleteRegionBoundary(definitions, point)
            SectionWandTool.REGION_CREATE -> SectionAuthoringActions.placeRegionBoundary(topology, definitions, point,
                maxRegionDistanceMeters, publication.regions.compilationSnapshot()!!.regionsById.mapValues { it.value.interior })
        }
        require(active() && current(capture)) { "Selection expired before submission" }
        val result = manager.applyDefinitionPatch(captured.worldId, definitions.definitionRevision, edit.patch,
            captured.runtime, topology, active)
        return if (result.status == SimpleSignalsDefinitionAuthoringStatus.APPLIED) {
            val next = if (continuation != null) capture(captured.worldId) as? Capture else null
            val retain = next?.publication?.definitions === result.definitions
            SectionWandOutcome("${edit.message}; definition revision ${result.definitions!!.definitionRevision}",
                continuation.takeIf { retain }, next.takeIf { retain })
        } else SectionWandOutcome(SimpleSignalsAdminCommand.formatResult(result), firstPoint, captured)
    }
}
