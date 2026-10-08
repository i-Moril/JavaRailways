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
import work.art1st.javarailways.simplesignals.signal.FixedBlockSignalDefinition
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
    fun resolveEntrance(capture: SignalWandCapture, point: RailPositionSnapshot): SignalWandBinding
    fun place(
        capture: SignalWandCapture,
        binding: SignalWandBinding,
        point: RailPositionSnapshot,
        active: () -> Boolean
    ): SignalWandOutcome
    fun delete(
        capture: SignalWandCapture,
        point: RailPositionSnapshot,
        active: () -> Boolean
    ): SignalWandOutcome
}

internal class RuntimeSignalWandBackend(
    private val manager: SimpleSignalsWorldRuntimeManager,
    private val infrastructure: InfrastructureResourceService,
    private val newSignalId: () -> String = { "simplesignals:${UUID.randomUUID()}" }
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

    override fun resolveEntrance(capture: SignalWandCapture, point: RailPositionSnapshot): SignalWandBinding {
        val captured = capture as Capture
        require(current(captured)) { "Signal selection expired; select again" }
        val resolved = FixedBlockSignalEntranceResolver.resolve(
            captured.publication.fixedBlocks.snapshot(captured.topology),
            captured.publication.definitions.fixedBlockSignals,
            point
        )
        return SignalWandBinding(captured.worldId, resolved.blockId, resolved.entryDirection)
    }

    override fun place(
        capture: SignalWandCapture,
        binding: SignalWandBinding,
        point: RailPositionSnapshot,
        active: () -> Boolean
    ): SignalWandOutcome {
        val captured = capture as Capture
        fun selectionCurrent(): Boolean = active() && current(captured)
        require(selectionCurrent()) { "Signal selection expired; select again" }
        require(binding.worldId == captured.worldId) { "The bound entrance belongs to another world" }
        val publication = captured.publication
        val blockSnapshot = publication.fixedBlocks.snapshot(captured.topology)
        val resolved = FixedBlockSignalEntranceResolver.resolve(
            blockSnapshot, publication.definitions.fixedBlockSignals, binding)
        val placement = ModelPlacement.RailAttached(point)
        val replacement = resolved.signal?.let { existing ->
            val nextRevision = try {
                Math.addExact(existing.revision, 1L)
            } catch (_: ArithmeticException) {
                throw IllegalArgumentException("Signal revision ${existing.revision} cannot be advanced")
            }
            existing.copy(revision = nextRevision, signalType = captured.signalType, placement = placement)
        } ?: FixedBlockSignalDefinition(
            uniqueSignalId(publication),
            0L,
            resolved.blockId,
            DEFAULT_INSPECTION_LEAD_TIME_SECONDS,
            captured.signalType,
            resolved.entryDirection,
            placement
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
            val verb = if (resolved.signal == null) "Created" else "Updated"
            SignalWandOutcome("$verb signal ${replacement.signalId} as ${replacement.signalType}; " +
                "definition revision ${result.definitions!!.definitionRevision}")
        } else {
            SignalWandOutcome(SimpleSignalsAdminCommand.formatResult(result))
        }
    }

    override fun delete(
        capture: SignalWandCapture,
        point: RailPositionSnapshot,
        active: () -> Boolean
    ): SignalWandOutcome {
        val captured = capture as Capture
        fun selectionCurrent(): Boolean = active() && current(captured)
        require(selectionCurrent()) { "Signal selection expired; select again" }
        val publication = captured.publication
        val resolved = FixedBlockSignalEntranceResolver.resolve(
            publication.fixedBlocks.snapshot(captured.topology),
            publication.definitions.fixedBlockSignals,
            point
        )
        val signal = resolved.signal
            ?: throw IllegalArgumentException("This fixed-block entrance has no primary signal to delete")
        val attachedIds = publication.definitions.attachedSignals
            .filter { it.primarySignalId == signal.signalId }
            .map { it.signalId }
        require(selectionCurrent()) { "Signal selection expired before submission" }
        val result = manager.applyDefinitionPatch(
            captured.worldId,
            publication.definitions.definitionRevision,
            SimpleSignalsDefinitionPatch(
                removeFixedBlockSignalIds = listOf(signal.signalId),
                removeAttachedSignalIds = attachedIds
            ),
            captured.runtime,
            captured.topology,
            ::selectionCurrent
        )
        return if (result.status == SimpleSignalsDefinitionAuthoringStatus.APPLIED) {
            SignalWandOutcome("Deleted signal ${signal.signalId} and ${attachedIds.size} attached device(s); " +
                "definition revision ${result.definitions!!.definitionRevision}")
        } else {
            SignalWandOutcome(SimpleSignalsAdminCommand.formatResult(result))
        }
    }

    private fun uniqueSignalId(publication: SimpleSignalsRuntimePublication): String {
        val candidate = newSignalId()
        val definitions = publication.definitions
        val deviceIds = buildSet {
            definitions.fixedBlockSignals.mapTo(this) { it.signalId }
            definitions.protectionSignals.mapTo(this) { it.signalId }
            definitions.routeIndicators.mapTo(this) { it.indicatorId }
            definitions.attachedSignals.mapTo(this) { it.signalId }
        }
        require(candidate !in deviceIds) { "Generated signal ID collides with an existing device: $candidate" }
        return candidate
    }

    private companion object {
        const val DEFAULT_INSPECTION_LEAD_TIME_SECONDS = 0.0
    }
}
