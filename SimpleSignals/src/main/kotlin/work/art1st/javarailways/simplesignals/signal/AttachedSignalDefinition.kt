package work.art1st.javarailways.simplesignals.signal

import work.art1st.javarailways.simplesignals.presentation.DeviceModelDefinition
import work.art1st.javarailways.simplesignals.region.SIMPLE_SIGNALS_NAMESPACED_ID

/** A display of one logical signal's output, with its own model and placement. */
internal data class AttachedSignalDefinition(
    val signalId: String,
    val revision: Long,
    val primarySignalId: String,
    val model: DeviceModelDefinition
) {
    init {
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(signalId)) { "attached signalId must be namespaced" }
        require(revision >= 0L) { "attached signal revision must be non-negative" }
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(primarySignalId)) { "primarySignalId must be namespaced" }
    }
}
