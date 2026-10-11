package work.art1st.javarailways.core.api.event

import org.jetbrains.annotations.ApiStatus
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.threading.ThreadGuard

/**
 * Synchronous main-thread decision before splitting [train] at a stable car identity.
 *
 * [train] is live and still unchanged. Listeners may only edit this decision, not the original
 * train, its extensions, or formal operating records. Unselected directions inherit
 * [originalReversed]. Conflicting requests reject the operation, regardless of listener priority.
 * Any listener failure also prevents submission, while remaining listeners still run.
 * The decision is frozen when dispatch returns; retaining it does not permit later edits.
 * Register through MainRailEventBus and close the registration when the owning plugin stops.
 * This supported event is experimental: signatures and semantics may change without source or
 * binary compatibility guarantees, including for Java callers.
 */
@ApiStatus.Experimental
class TrainSplitEvent internal constructor(
    override val train: Train,
    val carId: String,
    val newTrainId: String
) : MainThreadTrainEvent {
    /** Immutable original stable storage order, not the operating head-to-tail view. */
    val originalCarIds: List<String> = java.util.List.copyOf(train.cars.map { it.id })
    val originalReversed: Boolean = train.isReversed

    var rejectionReason: String? = null
        private set
    val isRejected: Boolean get() = rejectionReason != null
    val retainedReversed: Boolean get() = retainedRequest ?: originalReversed
    val separatedReversed: Boolean get() = separatedRequest ?: originalReversed

    private var retainedRequest: Boolean? = null
    private var separatedRequest: Boolean? = null
    private var frozen = false

    /** Irrevocably reject this operation; the first diagnostic is retained. */
    fun reject(reason: String) {
        checkEditable()
        require(reason.isNotBlank()) { "split rejection reason must not be blank" }
        if (rejectionReason == null) rejectionReason = reason
    }

    /** Select the stable prefix's operating direction, without changing its physical orientation. */
    fun requestRetainedDirection(reversed: Boolean) {
        checkEditable()
        if (retainedRequest != null && retainedRequest != reversed) {
            reject("Conflicting retained train directions")
        } else {
            retainedRequest = reversed
        }
    }

    /** Select the stable suffix's operating direction independently of the retained prefix. */
    fun requestSeparatedDirection(reversed: Boolean) {
        checkEditable()
        if (separatedRequest != null && separatedRequest != reversed) {
            reject("Conflicting separated train directions")
        } else {
            separatedRequest = reversed
        }
    }

    internal fun freeze() {
        frozen = true
    }

    private fun checkEditable() {
        ThreadGuard.ensureMainThread("TrainSplitEvent decision")
        check(!frozen) { "split decision has already been dispatched" }
    }
}
