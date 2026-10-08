package work.art1st.javarailways.simplesignals.testing

import org.bukkit.plugin.Plugin
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.simpleato.api.inspection.InspectionDeclaration
import work.art1st.javarailways.basic.api.route.RailOperatingDirections
import work.art1st.javarailways.simplesignals.block.*
import work.art1st.javarailways.simplesignals.runtime.PublishedSectionSignal
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsWorldRuntime
import java.util.UUID
import work.art1st.javarailways.simplesignals.signal.FixedBlockSignalEntry
import work.art1st.javarailways.simplesignals.signal.*
import work.art1st.javarailways.simplesignals.presentation.DeviceModelDefinition
import work.art1st.javarailways.simplesignals.presentation.ModelPlacement

/** Rebuild through the immutable domain constructor; its generated copy deliberately stays private. */
internal fun FixedBlockSignalDefinition.reconfigured(
    signalId: String = this.signalId,
    revision: Long = this.revision,
    entryDirection: FixedBlockEntryDirection = this.entryDirection,
    inspectionLeadTimeSeconds: Double = this.inspectionLeadTimeSeconds,
    clearLevels: Collection<FixedBlockSignalClearLevel> = this.clearLevels,
    downstreamSignals: Collection<FixedBlockSignalDependency> = this.downstreamSignals,
    model: DeviceModelDefinition? = this.model,
    packageSignalId: String? = this.packageSignalId,
    placement: ModelPlacement? = this.placement
) = FixedBlockSignalDefinition(signalId, revision, controlledBlockId, inspectionLeadTimeSeconds,
    occupiedOutput, clearLevels, downstreamSignals, model, entryDirection, packageSignalId, placement)

/** Legacy normal-control fixtures explicitly supply the per-rail policy their definitions require. */
internal fun authoredDirectionPolicy(snapshot: () -> FixedBlockReadSnapshot): FixedBlockDirectionPolicy =
    FixedBlockDirectionPolicy({ topology, rails ->
        val captured = snapshot()
        check(captured.topology === topology)
        val policies = captured.blocksById.values.flatMap { block -> block.traversals.map {
            it.railId to if (block.definition.bidirectional) null else it.facing
        } }.toMap()
        RailOperatingDirections(topology, rails.associateWith { policies[it] })
    })

internal fun signalWorldRuntime(worldId: UUID): SimpleSignalsWorldRuntime {
    lateinit var runtime: SimpleSignalsWorldRuntime
    runtime = SimpleSignalsWorldRuntime(worldId, authoredDirectionPolicy { runtime.fixedBlockSnapshot() })
    return runtime
}

internal fun fixedBlockPolicy(plugin: Plugin, snapshotForWorld: (UUID) -> FixedBlockReadSnapshot?): FixedBlockRouteResourcePolicy =
    FixedBlockRouteResourcePolicy(plugin, { world -> authoredDirectionPolicy { checkNotNull(snapshotForWorld(world)) } }, snapshotForWorld = snapshotForWorld)

internal fun fixedBlockInspectionSource(
    snapshot: () -> FixedBlockReadSnapshot,
    signalState: (FixedBlockSignalEntry) -> PublishedSectionSignal? = { null },
    hasConfiguredSignal: (FixedBlockSignalEntry) -> Boolean = { false },
    inspectionLeadTimeSeconds: (FixedBlockReadSnapshot, FixedBlockSignalEntry) -> Double = { _, _ -> 0.0 },
    protectionDeclarations: (FixedBlockReadSnapshot, RouteSnapshot) -> List<InspectionDeclaration> = { _, _ -> emptyList() },
    protectionSignalState: (String) -> PublishedSectionSignal? = { null },
    hasConfiguredProtectionSignal: (String) -> Boolean = { false }
): FixedBlockInspectionSource = FixedBlockInspectionSource(snapshot, signalState, hasConfiguredSignal,
    inspectionLeadTimeSeconds, protectionDeclarations, protectionSignalState, hasConfiguredProtectionSignal,
    authoredDirectionPolicy(snapshot))
