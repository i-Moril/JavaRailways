package work.art1st.javarailways.simplesignals.signal

import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.simplesignals.presentation.DeviceModelDefinition
import work.art1st.javarailways.simplesignals.region.SIMPLE_SIGNALS_NAMESPACED_ID

/** Relative to the complete authored block path, not an individual rail's AB/BA facing. */
internal enum class FixedBlockEntryDirection { FORWARD, REVERSE }

internal data class FixedBlockSignalEntry(
    val blockId: String,
    val direction: FixedBlockEntryDirection = FixedBlockEntryDirection.FORWARD
)

/** One configured signal presentation and the speed limit it contributes. */
internal data class ConfiguredSignalOutput(
    val speedLimitMetersPerSecond: Double,
    val modelVariant: String
) {
    init {
        require(speedLimitMetersPerSecond.isFinite() && speedLimitMetersPerSecond >= 0.0) {
            "configured signal speed limit must be finite and non-negative"
        }
        require(modelVariant.isNotBlank()) { "configured signal model variant must not be blank" }
    }
}

/** One explicitly selected turnout continuation along a downstream signal path. */
internal data class FixedBlockSignalContinuationRequirement(
    val entry: RailEndpointRef,
    val selectedExit: RailEndpointRef
) {
    init {
        require(entry != selectedExit) { "turnout continuation cannot return through its entry" }
    }
}

/** One downstream signal whose published limit may restrict this signal. */
@ConsistentCopyVisibility
internal data class FixedBlockSignalDependency private constructor(
    val signalId: String,
    val requiredContinuations: List<FixedBlockSignalContinuationRequirement>
) {
    constructor(
        signalId: String,
        requiredContinuations: Collection<FixedBlockSignalContinuationRequirement> = emptyList()
    ) : this(signalId, java.util.List.copyOf(requiredContinuations))

    init {
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(signalId)) {
            "downstream signalId must be namespaced"
        }
        require(requiredContinuations.map { it.entry }.distinct().size == requiredContinuations.size) {
            "downstream signal continuation entries must be unique"
        }
    }
}

/**
 * A configured clear-section output selected from the most restrictive downstream speed.
 *
 * The highest level whose threshold does not exceed the downstream speed is selected. A first
 * threshold of zero makes the table total for every valid downstream output.
 */
internal data class FixedBlockSignalClearLevel(
    val minimumDownstreamSpeedMetersPerSecond: Double,
    val output: ConfiguredSignalOutput
) {
    init {
        require(
            minimumDownstreamSpeedMetersPerSecond.isFinite() &&
                minimumDownstreamSpeedMetersPerSecond >= 0.0
        ) {
            "minimum downstream speed must be finite and non-negative"
        }
    }
}

/** Current authoring model for one fixed-block entry signal. */
@ConsistentCopyVisibility
internal data class FixedBlockSignalDefinition private constructor(
    val signalId: String,
    val revision: Long,
    val controlledBlockId: String,
    val inspectionLeadTimeSeconds: Double,
    val occupiedOutput: ConfiguredSignalOutput,
    val clearLevels: List<FixedBlockSignalClearLevel>,
    val downstreamSignals: List<FixedBlockSignalDependency>,
    val model: DeviceModelDefinition?,
    val entryDirection: FixedBlockEntryDirection,
    /** Player-facing type from the unified signal-pack catalog; null keeps the configured Kotlin rule. */
    val packageSignalId: String?
) {
    constructor(
        signalId: String,
        revision: Long,
        controlledBlockId: String,
        inspectionLeadTimeSeconds: Double,
        occupiedOutput: ConfiguredSignalOutput,
        clearLevels: Collection<FixedBlockSignalClearLevel>,
        downstreamSignals: Collection<FixedBlockSignalDependency> = emptyList(),
        model: DeviceModelDefinition? = null,
        entryDirection: FixedBlockEntryDirection = FixedBlockEntryDirection.FORWARD,
        packageSignalId: String? = null
    ) : this(
        signalId,
        revision,
        controlledBlockId,
        inspectionLeadTimeSeconds,
        occupiedOutput,
        java.util.List.copyOf(clearLevels),
        java.util.List.copyOf(downstreamSignals),
        model,
        entryDirection,
        packageSignalId
    )

    val entry: FixedBlockSignalEntry get() = FixedBlockSignalEntry(controlledBlockId, entryDirection)

    init {
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(signalId)) { "signalId must be namespaced" }
        require(packageSignalId == null || SIMPLE_SIGNALS_NAMESPACED_ID.matches(packageSignalId)) {
            "packageSignalId must be null or namespaced"
        }
        require(revision >= 0L) { "signal revision must be non-negative" }
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(controlledBlockId)) {
            "controlledBlockId must be namespaced"
        }
        require(inspectionLeadTimeSeconds.isFinite() && inspectionLeadTimeSeconds >= 0.0) {
            "inspection lead time must be finite and non-negative"
        }
        require(clearLevels.isNotEmpty()) { "fixed-block signal must define at least one clear level" }
        require(clearLevels.first().minimumDownstreamSpeedMetersPerSecond == 0.0) {
            "first fixed-block signal clear level must start at downstream speed 0"
        }
        clearLevels.zipWithNext().forEach { (left, right) ->
            require(
                left.minimumDownstreamSpeedMetersPerSecond <
                    right.minimumDownstreamSpeedMetersPerSecond
            ) {
                "fixed-block signal clear-level thresholds must be strictly increasing"
            }
        }
        require(downstreamSignals.map(FixedBlockSignalDependency::signalId).distinct().size ==
            downstreamSignals.size
        ) {
            "downstream signal IDs must be unique"
        }
    }
}
