package work.art1st.javarailways.simplesignals.block

import work.art1st.javarailways.simpleato.api.inspection.InspectionDeclaration
import work.art1st.javarailways.simpleato.api.inspection.InspectionDeclarationBinding
import work.art1st.javarailways.simpleato.api.inspection.InspectionSource
import work.art1st.javarailways.simpleato.api.restriction.RouteSpeedRestriction
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionSnapshot
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding
import work.art1st.javarailways.simplesignals.runtime.PublishedSectionSignal
import java.util.concurrent.atomic.AtomicLong

internal const val FIXED_BLOCK_SIGNAL_SOURCE_ID = "simplesignals:fixed-block-signals"

/** Compiles plugin-owned fixed blocks into neutral SimpleATO inspection declarations. */
internal class FixedBlockInspectionSource(
    private val snapshot: () -> FixedBlockReadSnapshot,
    private val signalState: (String) -> PublishedSectionSignal? = { null },
    private val inspectionDistanceMeters: (FixedBlockReadSnapshot, String) -> Double = { _, _ -> 0.0 }
) {
    private val nextRevision = AtomicLong(0L)

    val source = InspectionSource(
        sourceId = FIXED_BLOCK_SIGNAL_SOURCE_ID,
        bindingProvider = { input ->
            val captured = snapshot()
            val windows = when (val matched = FixedBlockRouteMatcher.matchRoute(captured, input.route)) {
                is FixedBlockAuthorityMatchResult.Matched -> matched.windows
                is FixedBlockAuthorityMatchResult.Rejected -> error(matched.detail)
            }
            InspectionDeclarationBinding(
                sourceId = FIXED_BLOCK_SIGNAL_SOURCE_ID,
                trainId = input.trainId,
                route = FrozenRouteBinding.from(input.route),
                definitionRevision = captured.definitionRevision,
                trainProfileRevision = 0L,
                declarations = windows.map { window ->
                    val block = captured.blocksById.getValue(window.blockId)
                    val entry = block.traversals.first()
                    InspectionDeclaration(
                        declarationId = window.blockId,
                        railId = entry.railId,
                        facing = entry.facing,
                        triggerRouteDistanceMeters = window.entryRouteDistanceMeters,
                        inspectionDistanceMeters = inspectionDistanceMeters(captured, window.blockId),
                        minimumRequestedAuthorityEndRouteDistanceMeters = window.exitRouteDistanceMeters
                    )
                }
            )
        },
        updateHandler = { input ->
            val revision = nextRevision.getAndUpdate { current -> Math.addExact(current, 1L) }
            val restrictions = input.activeDeclarations.mapNotNull { declaration ->
                val published = signalState(declaration.declarationId) ?: return@mapNotNull null
                require(published.stamp.worldId == input.observationStamp.worldId) {
                    "fixed-block signal ${declaration.declarationId} belongs to another world"
                }
                require(published.stamp.topologyRevision == input.observationStamp.topologyRevision) {
                    "fixed-block signal ${declaration.declarationId} uses stale topology"
                }
                require(published.stamp.physicsEpoch <= input.observationStamp.physicsEpoch) {
                    "fixed-block signal ${declaration.declarationId} is newer than the inspection observation"
                }
                RouteSpeedRestriction(
                    restrictionId = declaration.declarationId,
                    routeDistanceMeters = declaration.triggerRouteDistanceMeters,
                    maximumSpeedMetersPerSecond =
                        published.state.effectiveSpeedLimitMetersPerSecond
                )
            }
            listOf(
                TrainRestrictionSnapshot(
                    sourceId = FIXED_BLOCK_SIGNAL_SOURCE_ID,
                    revision = revision,
                    trainId = input.perception.train.trainId,
                    observationStamp = input.observationStamp,
                    route = input.route,
                    restrictions = restrictions
                )
            )
        }
    )
}
