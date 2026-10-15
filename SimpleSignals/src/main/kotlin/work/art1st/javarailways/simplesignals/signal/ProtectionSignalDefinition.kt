package work.art1st.javarailways.simplesignals.signal

import work.art1st.javarailways.simplesignals.presentation.DeviceModelDefinition
import work.art1st.javarailways.simplesignals.presentation.ModelPlacement
import work.art1st.javarailways.simplesignals.region.SIMPLE_SIGNALS_NAMESPACED_ID

@kotlinx.serialization.Serializable
internal enum class ProtectionSignalKind { FIXED_ROUTE, CURRENT_ENTRY }

/** One placed protection signal whose model and JavaScript logic come from [signalType]. */
internal data class ProtectionSignalDefinition(
    val signalId: String,
    val revision: Long,
    val kind: ProtectionSignalKind,
    /** Null explicitly leaves this retained device unbound; it never selects another route. */
    val bindingId: String?,
    val inspectionLeadTimeSeconds: Double,
    val signalType: String,
    val placement: ModelPlacement? = null
) {
    init {
        require(bindingId == null || SIMPLE_SIGNALS_NAMESPACED_ID.matches(bindingId)) {
            "protection bindingId must be namespaced"
        }
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(signalId)) { "signalId must be namespaced" }
        require(revision >= 0L) { "signal revision must be non-negative" }
        require(inspectionLeadTimeSeconds.isFinite() && inspectionLeadTimeSeconds >= 0.0) {
            "inspection lead time must be finite and non-negative"
        }
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(signalType)) { "signalType must be namespaced" }
    }
}

/** Route indicators and distant previews differ only in authored placement/model configuration. */
@ConsistentCopyVisibility
internal data class RouteIndicatorDefinition private constructor(
    val indicatorId: String,
    val revision: Long,
    val entryBoundaryId: String,
    val noRouteModelVariant: String,
    val modelVariantsByRouteId: java.util.SortedMap<String, String>,
    val model: DeviceModelDefinition?
) {
    constructor(indicatorId: String, revision: Long, entryBoundaryId: String,
        noRouteModelVariant: String, modelVariantsByRouteId: Map<String, String>,
        model: DeviceModelDefinition? = null
    ) : this(indicatorId, revision, entryBoundaryId, noRouteModelVariant,
        java.util.Collections.unmodifiableSortedMap(modelVariantsByRouteId.toSortedMap()), model)

    init {
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(indicatorId)) { "indicatorId must be namespaced" }
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(entryBoundaryId)) { "indicator entry must be namespaced" }
        require(revision >= 0) { "indicator revision must be non-negative" }
        require(noRouteModelVariant.isNotBlank() && modelVariantsByRouteId.values.all(String::isNotBlank)) {
            "indicator variants must not be blank"
        }
        require(modelVariantsByRouteId.keys.all(SIMPLE_SIGNALS_NAMESPACED_ID::matches)) {
            "indicator route IDs must be namespaced"
        }
    }
}

internal data class RouteIndicatorState(
    val selection: SelectedProtectionRoute,
    val modelVariant: String
)
