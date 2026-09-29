package work.art1st.javarailways.simplesignals.region

import work.art1st.javarailways.simpleato.api.inspection.InspectionDeclaration
import work.art1st.javarailways.simpleato.api.inspection.InspectionDeclarationBinding
import work.art1st.javarailways.simpleato.api.inspection.InspectionSource
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionSnapshot
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding
import java.util.concurrent.atomic.AtomicLong

internal const val PROTECTION_REGION_INSPECTION_SOURCE_ID =
    "simplesignals:protection-regions"

/** Compiles complete protection-region crossings into route-local authority floors. */
internal class ProtectionRegionInspectionSource(
    private val snapshot: () -> ProtectionRegionCompilationSnapshot
) {
    private val nextRevision = AtomicLong(0L)

    val source = InspectionSource(
        sourceId = PROTECTION_REGION_INSPECTION_SOURCE_ID,
        bindingProvider = { input ->
            val captured = snapshot()
            val windows = when (val matched = captured.match(input.route)) {
                is ProtectionRegionRouteMatchResult.Matched -> matched.windows
                is ProtectionRegionRouteMatchResult.Rejected -> error(
                    matched.diagnostics.joinToString { it.detail }
                )
            }
            InspectionDeclarationBinding(
                sourceId = PROTECTION_REGION_INSPECTION_SOURCE_ID,
                trainId = input.trainId,
                route = FrozenRouteBinding.from(input.route),
                definitionRevision = captured.definitionRevision,
                trainProfileRevision = 0L,
                declarations = windows.map { window ->
                    InspectionDeclaration(
                        declarationId = window.regionId,
                        railId = window.entry.position.railId,
                        facing = window.entry.position.facing,
                        triggerRouteDistanceMeters = window.entry.routeDistanceMeters,
                        inspectionDistanceMeters = 0.0,
                        minimumRequestedAuthorityEndRouteDistanceMeters =
                            window.exit.routeDistanceMeters
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
}
