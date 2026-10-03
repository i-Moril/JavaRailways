package work.art1st.javarailways.simplesignals.signal

import work.art1st.javarailways.simplesignals.presentation.DeviceModelDefinition
import work.art1st.javarailways.simplesignals.region.SIMPLE_SIGNALS_NAMESPACED_ID

@kotlinx.serialization.Serializable
internal enum class ProtectionSignalKind { FIXED_ROUTE, CURRENT_ENTRY }

/** Fixed devices observe their authored route; current devices consume the shared entry selector. */
@ConsistentCopyVisibility
internal data class ProtectionSignalDefinition private constructor(
    val signalId: String,
    val revision: Long,
    val kind: ProtectionSignalKind,
    val bindingId: String,
    val inspectionLeadTimeSeconds: Double,
    val unavailableOutput: ConfiguredSignalOutput,
    val occupiedOutput: ConfiguredSignalOutput,
    val clearLevels: List<FixedBlockSignalClearLevel>,
    val downstreamSignals: List<FixedBlockSignalDependency>,
    val downstreamSignalsByRouteId: Map<String, List<FixedBlockSignalDependency>>,
    val model: DeviceModelDefinition?
) {
    constructor(signalId: String, revision: Long, kind: ProtectionSignalKind, bindingId: String,
        inspectionLeadTimeSeconds: Double, unavailableOutput: ConfiguredSignalOutput,
        occupiedOutput: ConfiguredSignalOutput, clearLevels: Collection<FixedBlockSignalClearLevel>,
        downstreamSignals: Collection<FixedBlockSignalDependency> = emptyList(),
        downstreamSignalsByRouteId: Map<String, List<FixedBlockSignalDependency>> = emptyMap(),
        model: DeviceModelDefinition? = null
    ) : this(signalId, revision, kind, bindingId, inspectionLeadTimeSeconds, unavailableOutput,
        occupiedOutput, java.util.List.copyOf(clearLevels), java.util.List.copyOf(downstreamSignals),
        java.util.Map.copyOf(downstreamSignalsByRouteId.mapValues { java.util.List.copyOf(it.value) }), model)

    init {
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(bindingId)) { "protection bindingId must be namespaced" }
        require(unavailableOutput.speedLimitMetersPerSecond == 0.0) { "unready protection signal must stop" }
        // Reuse the configured output-table and identity contract.
        FixedBlockSignalDefinition(signalId, revision, bindingId, inspectionLeadTimeSeconds,
            occupiedOutput, clearLevels, downstreamSignals)
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
