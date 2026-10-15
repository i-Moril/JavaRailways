package work.art1st.javarailways.simplesignals.block

import work.art1st.javarailways.simpleato.api.inspection.InspectionDeclaration
import work.art1st.javarailways.simpleato.api.inspection.InspectionAuthorityRequestFloor
import work.art1st.javarailways.simpleato.api.inspection.InspectionDeclarationBinding
import work.art1st.javarailways.simpleato.api.inspection.InspectionSource
import work.art1st.javarailways.simpleato.api.restriction.RouteSpeedRestriction
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionSnapshot
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding
import work.art1st.javarailways.simplesignals.runtime.PublishedSectionSignal
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.simplesignals.signal.fixedBlockSignalDeclarationId
import work.art1st.javarailways.simplesignals.signal.blockEntryFromSignalDeclaration
import work.art1st.javarailways.simplesignals.signal.FixedBlockEntryDirection
import work.art1st.javarailways.simplesignals.signal.FixedBlockSignalEntry
import java.util.concurrent.atomic.AtomicLong

internal const val FIXED_BLOCK_SIGNAL_SOURCE_ID = "simplesignals:fixed-block-signals"

/** Compiles fixed blocks and protection signals into one neutral SimpleATO inspection source. */
internal class FixedBlockInspectionSource(
    private val snapshot: () -> FixedBlockReadSnapshot,
    private val signalState: (FixedBlockSignalEntry) -> PublishedSectionSignal? = { null },
    private val hasConfiguredSignal: (FixedBlockSignalEntry) -> Boolean = { false },
    private val inspectionLeadTimeSeconds: (FixedBlockReadSnapshot, FixedBlockSignalEntry) -> Double = { _, _ -> 0.0 },
    private val protectionDeclarations: (FixedBlockReadSnapshot, RouteSnapshot) -> List<InspectionDeclaration> = { _, _ -> emptyList() },
    private val protectionSignalState: (String) -> PublishedSectionSignal? = { null },
    private val hasConfiguredProtectionSignal: (String) -> Boolean = { false },
    private val directionPolicy: FixedBlockDirectionPolicy = FixedBlockDirectionPolicy()
) {
    private val nextRevision = AtomicLong(0L)

    val source = InspectionSource(
        sourceId = FIXED_BLOCK_SIGNAL_SOURCE_ID,
        bindingProvider = { input ->
            val captured = snapshot()
            val windows = matchRoute(captured, input.route)
            val disabled = directionPolicy.disabledBlocks(captured, windows.map { captured.blocksById.getValue(it.blockId) }.distinct())
            val normal = directionPolicy.normalWindows(captured, windows, disabled)
            InspectionDeclarationBinding(
                sourceId = FIXED_BLOCK_SIGNAL_SOURCE_ID,
                trainId = input.trainId,
                route = FrozenRouteBinding.from(input.route),
                definitionRevision = captured.definitionRevision,
                trainProfileRevision = 0L,
                declarations = normal.filter(FixedBlockRouteWindow::enteredAtBoundary).map { window ->
                    val block = captured.blocksById.getValue(window.blockId)
                    val entry = block.entryTraversal(window.reversed)
                    val signalEntry = FixedBlockSignalEntry(window.blockId,
                        if (window.reversed) FixedBlockEntryDirection.REVERSE else FixedBlockEntryDirection.FORWARD)
                    InspectionDeclaration(
                        declarationId = fixedBlockSignalDeclarationId(window.blockId, signalEntry.direction),
                        railId = entry.railId,
                        facing = entry.facing,
                        triggerRouteDistanceMeters = window.entryRouteDistanceMeters,
                        inspectionLeadTimeSeconds = inspectionLeadTimeSeconds(captured, signalEntry),
                        minimumRequestedAuthorityEndRouteDistanceMeters = window.exitRouteDistanceMeters
                    )
                } + protectionDeclarations(captured, input.route),
                authorityRequestFloors = normal.filterNot(FixedBlockRouteWindow::enteredAtBoundary).map { window ->
                    InspectionAuthorityRequestFloor(window.entryRouteDistanceMeters, window.exitRouteDistanceMeters)
                }
            )
        },
        routeInfrastructureDependencies = { route ->
            val captured = snapshot()
            val windows = matchRoute(captured, route)
            val disabled = directionPolicy.disabledBlocks(captured, windows.map { captured.blocksById.getValue(it.blockId) }.distinct())
            directionPolicy.normalWindows(captured, windows, disabled)
            java.util.Set.copyOf(windows.map { window ->
                StableInfrastructureResource(FIXED_BLOCK_RESOURCE_TYPE, window.blockId)
            })
        },
        terminalRouteContinuation = { route, proposedMovingRoute ->
            val captured = snapshot()
            val windows = matchRoute(captured, route)
            val disabled = directionPolicy.disabledBlocks(captured,
                windows.map { captured.blocksById.getValue(it.blockId) }.distinct())
            directionPolicy.normalWindows(captured, windows, disabled)
            val ownsTerminal = windows.any {
                kotlin.math.abs(it.exitRouteDistanceMeters - route.totalLengthMeters) <= 1.0e-6
            }
            if (ownsTerminal) {
                FixedBlockRouteMatcher.terminalContinuation(captured, route)
            } else {
                val proposedWindows = matchRoute(captured, proposedMovingRoute)
                check(proposedWindows.none { it.exitRouteDistanceMeters > route.totalLengthMeters + 1.0e-6 }) {
                    "moving terminal margin would enter a new fixed block"
                }
                // The shared source's existing compiler rejects incomplete protection traversal;
                // the host also refuses to invent a replacement path while proposing the margin.
                protectionDeclarations(captured, proposedMovingRoute)
                null
            }
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
                val entry = if (protection) null else blockEntryFromSignalDeclaration(declaration.declarationId)
                val published = if (protection) protectionSignalState(declaration.declarationId)
                    else signalState(checkNotNull(entry))
                if (published != null) {
                    require(published.stamp.worldId == input.observationStamp.worldId) {
                        "fixed-block signal ${declaration.declarationId} belongs to another world"
                    }
                    require(published.stamp.physicsEpoch <= input.observationStamp.physicsEpoch) {
                        "fixed-block signal ${declaration.declarationId} is newer than the inspection observation"
                    }
                } else if (!protection && !hasConfiguredSignal(checkNotNull(entry))) {
                    return@mapNotNull null
                }
                // An explicit unlimited result removes only the speed restriction. The binding's
                // declarations, authority floors and resource dependencies remain unchanged.
                val maximumSpeed = if (published == null) 0.0 else
                    published.state.effectiveSpeedLimitMetersPerSecond ?: return@mapNotNull null
                RouteSpeedRestriction(
                    restrictionId = declaration.declarationId,
                    routeDistanceMeters = declaration.triggerRouteDistanceMeters,
                    maximumSpeedMetersPerSecond = maximumSpeed
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
        return when (val matched = FixedBlockRouteMatcher.matchRoute(captured, route, detectReverse = true)) {
            is FixedBlockAuthorityMatchResult.Matched -> matched.windows
            is FixedBlockAuthorityMatchResult.Rejected -> error(matched.detail)
        }
    }
}
