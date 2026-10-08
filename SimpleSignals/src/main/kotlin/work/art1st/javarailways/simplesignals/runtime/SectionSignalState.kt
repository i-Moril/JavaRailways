package work.art1st.javarailways.simplesignals.runtime

import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef

/** One turnout selection required by a prepared route/observation branch. */
internal data class RequiredActiveContinuation(
    val entry: RailEndpointRef,
    val selectedExit: RailEndpointRef
) {
    init { require(entry != selectedExit) { "required continuation cannot return through its entry" } }
}

/** Validated JavaScript rule output; model variants remain opaque to the host runtime. */
internal data class SectionSignalOutput(
    val speedLimitMetersPerSecond: Double,
    val modelVariant: String
) {
    init {
        require(speedLimitMetersPerSecond.isFinite() && speedLimitMetersPerSecond >= 0.0) {
            "signal speed limit must be finite and non-negative"
        }
        require(modelVariant.isNotBlank()) { "signal model variant must not be blank" }
    }
}

/** Unavailable state is the host-owned fail-closed result, not a model variant. */
internal sealed interface SectionSignalState {
    val effectiveSpeedLimitMetersPerSecond: Double

    data class Available(val output: SectionSignalOutput) : SectionSignalState {
        override val effectiveSpeedLimitMetersPerSecond: Double get() = output.speedLimitMetersPerSecond
    }

    data class Unavailable(val reason: String) : SectionSignalState {
        init { require(reason.isNotBlank()) { "unavailable signal reason must not be blank" } }
        override val effectiveSpeedLimitMetersPerSecond: Double get() = 0.0
    }
}

/** Complete immutable signal outputs after one successful physical/signal epoch. */
internal class SectionSignalSnapshot(
    val stamp: RailwayStateStamp,
    states: Map<String, SectionSignalState>,
    changedSignalIds: Collection<String>
) {
    val states: Map<String, SectionSignalState> = java.util.Collections.unmodifiableMap(states.toSortedMap())
    val changedSignalIds: Set<String> = java.util.Collections.unmodifiableSet(changedSignalIds.toSortedSet())
}

internal data class PublishedSectionSignal(
    val stamp: RailwayStateStamp,
    val state: SectionSignalState
)
