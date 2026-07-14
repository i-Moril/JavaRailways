package work.art1st.mineRails.core.api.physics.rail

/** Operational object that can be observed at a rail location. */
interface RailObject {
    /** Stable world-scoped identity used for observation diffing and persistence references. */
    val ref: RailObjectRef

    /** Current published lifecycle. Removed objects must no longer be returned by world indexes. */
    val lifecycleState: RailObjectLifecycleState

    val location: RailLocation
}
