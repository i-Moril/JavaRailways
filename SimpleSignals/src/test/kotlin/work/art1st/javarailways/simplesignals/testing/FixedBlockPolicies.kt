package work.art1st.javarailways.simplesignals.testing

import org.bukkit.plugin.Plugin
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.simpleato.api.inspection.InspectionDeclaration
import work.art1st.javarailways.basic.api.route.RailOperatingDirections
import work.art1st.javarailways.simplesignals.block.*
import work.art1st.javarailways.simplesignals.runtime.PublishedSectionSignal
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsWorldRuntime
import java.util.UUID

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
    FixedBlockRouteResourcePolicy(plugin, { world -> authoredDirectionPolicy { checkNotNull(snapshotForWorld(world)) } }, snapshotForWorld)

internal fun fixedBlockInspectionSource(
    snapshot: () -> FixedBlockReadSnapshot,
    signalState: (String) -> PublishedSectionSignal? = { null },
    hasConfiguredSignal: (String) -> Boolean = { false },
    inspectionLeadTimeSeconds: (FixedBlockReadSnapshot, String) -> Double = { _, _ -> 0.0 },
    protectionDeclarations: (FixedBlockReadSnapshot, RouteSnapshot) -> List<InspectionDeclaration> = { _, _ -> emptyList() },
    protectionSignalState: (String) -> PublishedSectionSignal? = { null },
    hasConfiguredProtectionSignal: (String) -> Boolean = { false }
): FixedBlockInspectionSource = FixedBlockInspectionSource(snapshot, signalState, hasConfiguredSignal,
    inspectionLeadTimeSeconds, protectionDeclarations, protectionSignalState, hasConfiguredProtectionSignal,
    authoredDirectionPolicy(snapshot))
