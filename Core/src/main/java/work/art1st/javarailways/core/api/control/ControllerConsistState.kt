package work.art1st.javarailways.core.api.control

import org.jetbrains.annotations.ApiStatus

/**
 * Controller-owned state replaced only after structural preparation succeeds.
 * [T] must be a prepared, immutable value: constructing it may fail, installing it must not.
 * Read/prepare on the simulation owner with physics excluded, or read on its physics worker.
 * The cell is runtime-only and does not publish a multi-cell cross-thread snapshot.
 */
@ApiStatus.Experimental
class ControllerConsistState<T>(initialValue: T) {
    /** Current live reference; non-blocking O(1) read, not a defensive copy or a published snapshot. */
    var value: T = initialValue
        private set

    /**
     * Prepare an O(1), non-blocking reference replacement without installing it or copying [value].
     * Call on the structural simulation owner; the caller must finish all fallible derivation first.
     * The returned runtime-only update is installed by Core only if the structure operation succeeds.
     */
    fun prepare(value: T): PreparedControllerStateUpdate = PreparedControllerStateUpdate.create(this, value)

    internal fun install(value: T) {
        this.value = value
    }
}

/**
 * Opaque structural update constructed by Core, never a plugin-supplied commit callback.
 * Core installs it using a plain reference assignment while physics is excluded.
 */
@ApiStatus.Experimental
class PreparedControllerStateUpdate private constructor(private val assignment: () -> Unit) {
    internal fun install() = assignment()

    /** Core-owned construction; consumers prepare updates through their typed state cell. */
    companion object {
        internal fun <T> create(cell: ControllerConsistState<T>, value: T): PreparedControllerStateUpdate =
            PreparedControllerStateUpdate { cell.install(value) }
    }
}
