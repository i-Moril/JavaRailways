package work.art1st.javarailways.simplesignals.signal

import work.art1st.javarailways.simplesignals.region.SIMPLE_SIGNALS_NAMESPACED_ID

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

/** One downstream signal whose published limit may restrict this signal. */
internal data class FixedBlockSignalDependency(
    val signalId: String,
    val distanceMeters: Double
) {
    init {
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(signalId)) {
            "downstream signalId must be namespaced"
        }
        require(distanceMeters.isFinite() && distanceMeters >= 0.0) {
            "downstream signal distance must be finite and non-negative"
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
    val downstreamSignals: List<FixedBlockSignalDependency>
) {
    constructor(
        signalId: String,
        revision: Long,
        controlledBlockId: String,
        inspectionLeadTimeSeconds: Double,
        occupiedOutput: ConfiguredSignalOutput,
        clearLevels: Collection<FixedBlockSignalClearLevel>,
        downstreamSignals: Collection<FixedBlockSignalDependency> = emptyList()
    ) : this(
        signalId,
        revision,
        controlledBlockId,
        inspectionLeadTimeSeconds,
        occupiedOutput,
        java.util.List.copyOf(clearLevels),
        java.util.List.copyOf(downstreamSignals)
    )

    init {
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(signalId)) { "signalId must be namespaced" }
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
