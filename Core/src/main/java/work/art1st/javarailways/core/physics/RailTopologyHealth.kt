package work.art1st.javarailways.core.physics

/** Stable reason why this world's structural topology can no longer be trusted. */
internal enum class RailTopologyFaultReason {
    TRANSACTION_FAILED_AFTER_STRUCTURAL_MUTATION
}

/**
 * Lightweight world-local fault record. The original throwable is deliberately not retained for
 * the lifetime of the world; the failing call still propagates it to its immediate caller.
 */
internal data class RailTopologyFault(
    val reason: RailTopologyFaultReason,
    val lastPublishedRevision: Long,
    val failureType: String,
    val failureMessage: String?
)

internal sealed interface RailTopologyHealth {
    data object Healthy : RailTopologyHealth
    data class Faulted(val fault: RailTopologyFault) : RailTopologyHealth
}

internal class RailTopologyFaultedException(
    val fault: RailTopologyFault
) : IllegalStateException(
    "Rail topology is faulted (${fault.reason}) after revision ${fault.lastPublishedRevision}; " +
        "reload the world before further publication or structural edits. " +
        "Origin: ${fault.failureType}${fault.failureMessage?.let { ": $it" }.orEmpty()}"
)
