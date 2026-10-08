package work.art1st.javarailways.simplesignals.authoring

import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceService
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.simplesignals.command.SimpleSignalsAdminCommand
import work.art1st.javarailways.simplesignals.presentation.ModelPlacement
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsDefinitionAuthoringStatus
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsDefinitionPatch
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsRuntimePublication
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsWorldRuntime
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsWorldRuntimeManager
import work.art1st.javarailways.simplesignals.signalpack.AvailableSignalType
import work.art1st.javarailways.simplesignals.signalpack.SignalTypeCatalogSnapshot
import java.util.UUID

internal interface SignalWandCapture { val worldId: UUID }

internal data class SignalWandOutcome(val message: String)

/** Worker-facing boundary. Catalog data exposed to commands is immutable intent, not edit authority. */
internal interface SignalWandBackend {
    fun availableSignalTypes(): List<AvailableSignalType>?
    fun capture(worldId: UUID, signalType: String): SignalWandCapture
    fun current(capture: SignalWandCapture): Boolean
    fun perform(capture: SignalWandCapture, point: RailPositionSnapshot, active: () -> Boolean): SignalWandOutcome
}

internal class RuntimeSignalWandBackend(
    private val manager: SimpleSignalsWorldRuntimeManager,
    private val infrastructure: InfrastructureResourceService
) : SignalWandBackend {
    private data class Capture(
        override val worldId: UUID,
        val signalType: String,
        val catalog: SignalTypeCatalogSnapshot,
        val runtime: SimpleSignalsWorldRuntime,
        val publication: SimpleSignalsRuntimePublication,
        val topology: TopologyState
    ) : SignalWandCapture

    override fun availableSignalTypes(): List<AvailableSignalType>? =
        manager.signalTypeCatalogSnapshot()?.signalTypes

    override fun capture(worldId: UUID, signalType: String): SignalWandCapture {
        val catalog = manager.signalTypeCatalogSnapshot()
            ?: throw IllegalStateException("Signal type catalog is not ready")
        require(catalog.contains(signalType)) { "Unknown signal type: $signalType" }
        val runtime = manager.authoringRuntime(worldId)
            ?: throw IllegalStateException("SimpleSignals world is unavailable")
        return Capture(worldId, signalType, catalog, runtime, runtime.authoringPublication(),
            infrastructure.currentTopology(worldId))
    }

    override fun current(capture: SignalWandCapture): Boolean {
        val captured = capture as Capture
        return manager.currentSignalTypeCatalog(captured.catalog) &&
            manager.authoringRuntime(captured.worldId) === captured.runtime &&
            captured.runtime.authoringPublication() === captured.publication &&
            infrastructure.currentTopology(captured.worldId) === captured.topology
    }

    override fun perform(
        capture: SignalWandCapture,
        point: RailPositionSnapshot,
        active: () -> Boolean
    ): SignalWandOutcome {
        val captured = capture as Capture
        fun selectionCurrent(): Boolean = active() && current(captured)
        require(selectionCurrent()) { "Signal selection expired; select again" }
        val publication = captured.publication
        val blockSnapshot = publication.fixedBlocks.snapshot(captured.topology)
        val resolved = FixedBlockSignalEntranceResolver.resolve(
            blockSnapshot, publication.definitions.fixedBlockSignals, point)
        val nextRevision = try {
            Math.addExact(resolved.signal.revision, 1L)
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("Signal revision ${resolved.signal.revision} cannot be advanced")
        }
        val replacement = resolved.signal.copy(
            revision = nextRevision,
            signalType = captured.signalType,
            placement = ModelPlacement.RailAttached(point)
        )
        require(selectionCurrent()) { "Signal selection expired before submission" }
        val result = manager.applyDefinitionPatch(
            captured.worldId,
            publication.definitions.definitionRevision,
            SimpleSignalsDefinitionPatch(upsertFixedBlockSignals = listOf(replacement)),
            captured.runtime,
            captured.topology,
            ::selectionCurrent
        )
        return if (result.status == SimpleSignalsDefinitionAuthoringStatus.APPLIED) {
            SignalWandOutcome("Updated signal ${replacement.signalId} to ${replacement.signalType}; " +
                "definition revision ${result.definitions!!.definitionRevision}")
        } else {
            SignalWandOutcome(SimpleSignalsAdminCommand.formatResult(result))
        }
    }
}
