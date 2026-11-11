package work.art1st.javarailways.basic.api.route

import work.art1st.javarailways.core.api.util.annotation.MainThread

/** Current state of one resource-owned preparation attempt. */
enum class ResourcePreparationState {
    PREPARING,
    READY,
    ABORTED_SAFE,
    UNSAFE
}

/** Immutable observation of one [ResourcePreparationTicket]. */
data class ResourcePreparationSnapshot(
    val state: ResourcePreparationState,
    val detail: String = ""
) {
    init {
        require(state != ResourcePreparationState.UNSAFE || detail.isNotBlank()) {
            "UNSAFE resource preparation snapshots require detail"
        }
    }
}

/**
 * Process-local handle for one resource-owned preparation attempt.
 *
 * Implementations must return quickly on the world simulation owner. [abort] is idempotent and
 * asks the resource to reach its own safe aborted state; it does not request restoration of a
 * previous or default state. Tickets are runtime values and are never persisted.
 */
interface ResourcePreparationTicket {
    @MainThread
    fun snapshot(): ResourcePreparationSnapshot

    @MainThread
    fun abort()
}
