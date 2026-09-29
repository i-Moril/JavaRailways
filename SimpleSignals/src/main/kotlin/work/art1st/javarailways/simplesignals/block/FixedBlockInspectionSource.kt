package work.art1st.javarailways.simplesignals.block

import work.art1st.javarailways.simpleato.api.inspection.InspectionDeclaration
import work.art1st.javarailways.simpleato.api.inspection.InspectionDeclarationBinding
import work.art1st.javarailways.simpleato.api.inspection.InspectionSource
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionSnapshot
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding
import java.util.concurrent.atomic.AtomicLong

internal const val FIXED_BLOCK_INSPECTION_SOURCE_ID = "simplesignals:fixed-block-inspection"

/** Compiles plugin-owned fixed blocks into neutral SimpleATO inspection declarations. */
internal class FixedBlockInspectionSource(
    private val snapshot: () -> FixedBlockReadSnapshot
) {
    private val nextRevision = AtomicLong(0L)

    val source = InspectionSource(
        sourceId = FIXED_BLOCK_INSPECTION_SOURCE_ID,
        bindingProvider = { input ->
            val captured = snapshot()
            val windows = when (val matched = FixedBlockRouteMatcher.matchRoute(captured, input.route)) {
                is FixedBlockAuthorityMatchResult.Matched -> matched.windows
                is FixedBlockAuthorityMatchResult.Rejected -> error(matched.detail)
            }
            InspectionDeclarationBinding(
                sourceId = FIXED_BLOCK_INSPECTION_SOURCE_ID,
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
                        inspectionDistanceMeters = 0.0,
                        minimumRequestedAuthorityEndRouteDistanceMeters = window.exitRouteDistanceMeters
                    )
                }
            )
        },
        updateHandler = { input ->
            val revision = nextRevision.getAndUpdate { current -> Math.addExact(current, 1L) }
            listOf(
                TrainRestrictionSnapshot(
                    sourceId = FIXED_BLOCK_INSPECTION_SOURCE_ID,
                    revision = revision,
                    trainId = input.perception.train.trainId,
                    observationStamp = input.observationStamp,
                    route = input.route,
                    restrictions = emptyList()
                )
            )
        }
    )
}
