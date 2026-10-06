package work.art1st.javarailways.simplesignals.region

import work.art1st.javarailways.simpleato.api.inspection.InspectionDeclaration
import work.art1st.javarailways.simpleato.api.inspection.InspectionDeclarationBinding
import work.art1st.javarailways.simpleato.api.inspection.InspectionSource
import work.art1st.javarailways.simpleato.api.inspection.InspectionEntryClearance
import work.art1st.javarailways.simpleato.api.inspection.InspectionRestrictionIdentity
import work.art1st.javarailways.simplesignals.block.FIXED_BLOCK_SIGNAL_SOURCE_ID
import work.art1st.javarailways.simplesignals.signal.protectionSignalDeclarationId
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionSnapshot
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.simplesignals.block.FixedBlockRouteWindow
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsRuntimePublication
import java.util.concurrent.atomic.AtomicLong

internal const val PROTECTION_REGION_INSPECTION_SOURCE_ID =
    "simplesignals:protection-regions"

/** Compiles complete protection-region crossings into route-local authority floors. */
internal class ProtectionRegionInspectionSource(
    private val publication: () -> SimpleSignalsRuntimePublication
) {
    private val nextRevision = AtomicLong(0L)

    val source = InspectionSource(
        sourceId = PROTECTION_REGION_INSPECTION_SOURCE_ID,
        bindingProvider = { input ->
            val captured = publication()
            val matched = captured.routeBindingCompiler.compile(input.route)
            check(matched.diagnostics.isEmpty()) { matched.diagnostics.joinToString() }
            InspectionDeclarationBinding(
                sourceId = PROTECTION_REGION_INSPECTION_SOURCE_ID,
                trainId = input.trainId,
                route = FrozenRouteBinding.from(input.route),
                definitionRevision = captured.definitions.definitionRevision,
                trainProfileRevision = 0L,
                declarations = matched.windows.map { match ->
                    val window = match.region
                    InspectionDeclaration(
                        declarationId = window.regionId,
                        railId = window.entry.position.railId,
                        facing = window.entry.position.facing,
                        triggerRouteDistanceMeters = window.entry.routeDistanceMeters,
                        inspectionLeadTimeSeconds = 0.0,
                        minimumRequestedAuthorityEndRouteDistanceMeters =
                            match.floorMeters
                    )
                },
                entryClearances = matched.windows.map { match ->
                    InspectionEntryClearance(
                        match.region.regionId,
                        match.region.entry.routeDistanceMeters,
                        match.region.exit.routeDistanceMeters,
                        match.tableRoute?.let { route ->
                            captured.protectionSignals.signalIdsByRoute[route.definition.routeId].orEmpty().map {
                                InspectionRestrictionIdentity(FIXED_BLOCK_SIGNAL_SOURCE_ID, protectionSignalDeclarationId(it))
                            }
                        }.orEmpty()
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
        },
        routeInfrastructureDependencies = { route ->
            val matched = publication().routeBindingCompiler.compile(route)
            check(matched.diagnostics.isEmpty()) { matched.diagnostics.joinToString() }
            emptyList()
        }
    )

}

/** Shared exact floor used by inspection and by read-only signal readiness. */
internal fun protectionRegionAuthorityFloor(
        region: ProtectionRegionRouteWindow,
        blocks: List<FixedBlockRouteWindow>
    ): Double {
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
            ?: return region.exit.routeDistanceMeters
        val competing = blocks.getOrNull(low + 1)
            ?.takeIf { it.entryRouteDistanceMeters <= exit + Curve.TOLERANCE }
        if (competing != null) {
            error(
                "protection region ${region.regionId} has multiple fixed-block successors " +
                    "at route distance $exit: ${successor.blockId}, ${competing.blockId}"
            )
        }
        return successor.exitRouteDistanceMeters
    }
