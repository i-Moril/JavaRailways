package work.art1st.javarailways.simplesignals.region

import work.art1st.javarailways.simpleato.api.inspection.InspectionDeclaration
import work.art1st.javarailways.simpleato.api.inspection.InspectionDeclarationBinding
import work.art1st.javarailways.simpleato.api.inspection.InspectionSource
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionSnapshot
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.simplesignals.block.FixedBlockAuthorityMatchResult
import work.art1st.javarailways.simplesignals.block.FixedBlockReadSnapshot
import work.art1st.javarailways.simplesignals.block.FixedBlockRouteMatcher
import work.art1st.javarailways.simplesignals.block.FixedBlockRouteWindow
import java.util.concurrent.atomic.AtomicLong

internal const val PROTECTION_REGION_INSPECTION_SOURCE_ID =
    "simplesignals:protection-regions"

/** Compiles complete protection-region crossings into route-local authority floors. */
internal class ProtectionRegionInspectionSource(
    private val regionSnapshot: () -> ProtectionRegionCompilationSnapshot,
    private val fixedBlockSnapshot: () -> FixedBlockReadSnapshot
) {
    private val nextRevision = AtomicLong(0L)

    val source = InspectionSource(
        sourceId = PROTECTION_REGION_INSPECTION_SOURCE_ID,
        bindingProvider = { input ->
            val capturedRegions = regionSnapshot()
            val capturedBlocks = fixedBlockSnapshot()
            check(capturedRegions.topology === capturedBlocks.topology) {
                "protection regions and fixed blocks were captured from different topology roots"
            }
            check(capturedRegions.definitionRevision == capturedBlocks.definitionRevision) {
                "protection regions and fixed blocks were captured from different definition revisions"
            }
            val regionWindows = when (val matched = capturedRegions.match(input.route)) {
                is ProtectionRegionRouteMatchResult.Matched -> matched.windows
                is ProtectionRegionRouteMatchResult.Rejected -> error(
                    matched.diagnostics.joinToString { it.detail }
                )
            }
            val blockWindows = when (
                val matched = FixedBlockRouteMatcher.matchRoute(capturedBlocks, input.route)
            ) {
                is FixedBlockAuthorityMatchResult.Matched -> matched.windows
                is FixedBlockAuthorityMatchResult.Rejected -> error(matched.detail)
            }
            InspectionDeclarationBinding(
                sourceId = PROTECTION_REGION_INSPECTION_SOURCE_ID,
                trainId = input.trainId,
                route = FrozenRouteBinding.from(input.route),
                definitionRevision = capturedRegions.definitionRevision,
                trainProfileRevision = 0L,
                declarations = regionWindows.map { window ->
                    val successor = immediateSuccessor(window, blockWindows)
                    InspectionDeclaration(
                        declarationId = window.regionId,
                        railId = window.entry.position.railId,
                        facing = window.entry.position.facing,
                        triggerRouteDistanceMeters = window.entry.routeDistanceMeters,
                        inspectionLeadTimeSeconds = 0.0,
                        minimumRequestedAuthorityEndRouteDistanceMeters =
                            successor?.exitRouteDistanceMeters
                                ?: window.exit.routeDistanceMeters
                    )
                }
            )
        },
        updateHandler = { input ->
            val revision = nextRevision.getAndUpdate { current -> Math.addExact(current, 1L) }
            listOf(
                TrainRestrictionSnapshot(
                    sourceId = PROTECTION_REGION_INSPECTION_SOURCE_ID,
                    revision = revision,
                    trainId = input.perception.train.trainId,
                    observationStamp = input.observationStamp,
                    route = input.route,
                    restrictions = emptyList()
                )
            )
        }
    )

    /** Finds the tolerance window in the already entry-sorted fixed-block result. */
    private fun immediateSuccessor(
        region: ProtectionRegionRouteWindow,
        blocks: List<FixedBlockRouteWindow>
    ): FixedBlockRouteWindow? {
        val exit = region.exit.routeDistanceMeters
        var low = 0
        var high = blocks.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (blocks[middle].entryRouteDistanceMeters < exit - Curve.TOLERANCE) {
                low = middle + 1
            } else {
                high = middle
            }
        }
        val successor = blocks.getOrNull(low)
            ?.takeIf { it.entryRouteDistanceMeters <= exit + Curve.TOLERANCE }
            ?: return null
        val competing = blocks.getOrNull(low + 1)
            ?.takeIf { it.entryRouteDistanceMeters <= exit + Curve.TOLERANCE }
        if (competing != null) {
            error(
                "protection region ${region.regionId} has multiple fixed-block successors " +
                    "at route distance $exit: ${successor.blockId}, ${competing.blockId}"
            )
        }
        return successor
    }
}
