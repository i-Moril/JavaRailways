package work.art1st.javarailways.simplesignals.block

import work.art1st.javarailways.simpleato.api.inspection.InspectionDeclaration
import work.art1st.javarailways.simpleato.api.inspection.InspectionDeclarationBinding
import work.art1st.javarailways.simpleato.api.inspection.InspectionSource
import work.art1st.javarailways.simpleato.api.restriction.RouteSpeedRestriction
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionSnapshot
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding
import work.art1st.javarailways.simplesignals.runtime.PublishedSectionSignal
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.simplesignals.signal.fixedBlockSignalDeclarationId
import work.art1st.javarailways.simplesignals.signal.blockIdFromSignalDeclaration
import java.util.concurrent.atomic.AtomicLong

internal const val FIXED_BLOCK_SIGNAL_SOURCE_ID = "simplesignals:fixed-block-signals"

/** Compiles fixed blocks and protection signals into one neutral SimpleATO inspection source. */
internal class FixedBlockInspectionSource(
    private val snapshot: () -> FixedBlockReadSnapshot,
    private val signalState: (String) -> PublishedSectionSignal? = { null },
    private val hasConfiguredSignal: (String) -> Boolean = { false },
    private val inspectionLeadTimeSeconds: (FixedBlockReadSnapshot, String) -> Double = { _, _ -> 0.0 },
    private val protectionDeclarations: (FixedBlockReadSnapshot, RouteSnapshot) -> List<InspectionDeclaration> = { _, _ -> emptyList() },
    private val protectionSignalState: (String) -> PublishedSectionSignal? = { null },
    private val hasConfiguredProtectionSignal: (String) -> Boolean = { false }
) {
    private val nextRevision = AtomicLong(0L)

    val source = InspectionSource(
        sourceId = FIXED_BLOCK_SIGNAL_SOURCE_ID,
        bindingProvider = { input ->
            val captured = snapshot()
            val windows = matchRoute(captured, input.route)
            InspectionDeclarationBinding(
                sourceId = FIXED_BLOCK_SIGNAL_SOURCE_ID,
                trainId = input.trainId,
                route = FrozenRouteBinding.from(input.route),
                definitionRevision = captured.definitionRevision,
                trainProfileRevision = 0L,
                declarations = windows.filter(FixedBlockRouteWindow::enteredAtBoundary).map { window ->
                    val block = captured.blocksById.getValue(window.blockId)
                    val entry = block.entryTraversal(window.reversed)
                    InspectionDeclaration(
                        declarationId = fixedBlockSignalDeclarationId(window.blockId),
                        railId = entry.railId,
                        facing = entry.facing,
                        triggerRouteDistanceMeters = window.entryRouteDistanceMeters,
                        inspectionLeadTimeSeconds = inspectionLeadTimeSeconds(captured, window.blockId),
                        minimumRequestedAuthorityEndRouteDistanceMeters = window.exitRouteDistanceMeters
                    )
                } + protectionDeclarations(captured, input.route)
            )
        },
        routeInfrastructureDependencies = { route ->
            val captured = snapshot()
            java.util.Set.copyOf(matchRoute(captured, route).map { window ->
                StableInfrastructureResource(FIXED_BLOCK_RESOURCE_TYPE, window.blockId)
            })
        },
        updateHandler = { input ->
            val revision = nextRevision.getAndUpdate { current -> Math.addExact(current, 1L) }
            // One physical signal can occur repeatedly on a frozen route. Its shared state needs
            // one restriction at the nearest active occurrence, not duplicate restriction IDs.
            val nearestById = linkedMapOf<String, InspectionDeclaration>()
            input.activeDeclarations.forEach { declaration ->
                val previous = nearestById[declaration.declarationId]
                if (previous == null || declaration.triggerRouteDistanceMeters < previous.triggerRouteDistanceMeters) {
                    nearestById[declaration.declarationId] = declaration
                }
            }
            val restrictions = nearestById.values.mapNotNull { declaration ->
                val protection = hasConfiguredProtectionSignal(declaration.declarationId)
                val blockId = if (protection) null else blockIdFromSignalDeclaration(declaration.declarationId)
                val published = if (protection) protectionSignalState(declaration.declarationId)
                    else signalState(checkNotNull(blockId))
                if (published != null) {
                    require(published.stamp.worldId == input.observationStamp.worldId) {
                        "fixed-block signal ${declaration.declarationId} belongs to another world"
                    }
                    require(published.stamp.topologyRevision == input.observationStamp.topologyRevision) {
                        "fixed-block signal ${declaration.declarationId} uses stale topology"
                    }
                    require(published.stamp.physicsEpoch <= input.observationStamp.physicsEpoch) {
                        "fixed-block signal ${declaration.declarationId} is newer than the inspection observation"
                    }
                } else if (!protection && !hasConfiguredSignal(checkNotNull(blockId))) {
                    return@mapNotNull null
                }
                RouteSpeedRestriction(
                    restrictionId = declaration.declarationId,
                    routeDistanceMeters = declaration.triggerRouteDistanceMeters,
                    maximumSpeedMetersPerSecond =
                        published?.state?.effectiveSpeedLimitMetersPerSecond ?: 0.0
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

    private fun matchRoute(captured: FixedBlockReadSnapshot, route: RouteSnapshot): List<FixedBlockRouteWindow> {
        check(captured.topology.revision == route.topologyRevision) {
            "fixed-block definitions use stale topology for route ${route.routeId}"
        }
        return when (val matched = FixedBlockRouteMatcher.matchRoute(captured, route)) {
            is FixedBlockAuthorityMatchResult.Matched -> matched.windows
            is FixedBlockAuthorityMatchResult.Rejected -> error(matched.detail)
        }
    }
}
