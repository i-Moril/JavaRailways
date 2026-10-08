package work.art1st.javarailways.simplesignals.signal

import work.art1st.javarailways.simplesignals.presentation.ModelPlacement
import work.art1st.javarailways.simplesignals.region.SIMPLE_SIGNALS_NAMESPACED_ID

/** Relative to the complete authored block path, not an individual rail's AB/BA facing. */
internal enum class FixedBlockEntryDirection { FORWARD, REVERSE }

internal data class FixedBlockSignalEntry(
    val blockId: String,
    val direction: FixedBlockEntryDirection = FixedBlockEntryDirection.FORWARD
)

/**
 * One placed fixed-block signal.
 *
 * [signalId] identifies this world device. [signalType] identifies the reusable SimpleSignals
 * catalog type that supplies both its model and JavaScript logic. The catalog's storage/provider
 * origin is deliberately absent from the world model.
 */
internal data class FixedBlockSignalDefinition(
    val signalId: String,
    val revision: Long,
    val controlledBlockId: String,
    val inspectionLeadTimeSeconds: Double,
    val signalType: String,
    val entryDirection: FixedBlockEntryDirection = FixedBlockEntryDirection.FORWARD,
    val placement: ModelPlacement? = null
) {
    val entry: FixedBlockSignalEntry get() = FixedBlockSignalEntry(controlledBlockId, entryDirection)

    init {
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(signalId)) { "signalId must be namespaced" }
        require(revision >= 0L) { "signal revision must be non-negative" }
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(controlledBlockId)) {
            "controlledBlockId must be namespaced"
        }
        require(inspectionLeadTimeSeconds.isFinite() && inspectionLeadTimeSeconds >= 0.0) {
            "inspection lead time must be finite and non-negative"
        }
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(signalType)) { "signalType must be namespaced" }
    }
}
