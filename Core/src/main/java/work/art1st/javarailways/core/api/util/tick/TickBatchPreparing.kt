package work.art1st.javarailways.core.api.util.tick

/**
 * A registered [TickingObject] whose work must be captured before workers are submitted.
 *
 * The TickManager owner calls [prepareTick] once per preTick, before any object in that
 * batch starts ticking. Implementations capture their own live pending work synchronously;
 * work submitted after capture belongs to the following tick. This does not run commits.
 * Registration and removal follow [TickManager.register] and [TickManager.unregister].
 * Failure propagates to the preTick caller and prevents worker submission for this batch.
 */
interface TickBatchPreparing : TickingObject {
    /** Captures this object's pending work on the owner before any worker in the batch starts. */
    fun prepareTick()
}
