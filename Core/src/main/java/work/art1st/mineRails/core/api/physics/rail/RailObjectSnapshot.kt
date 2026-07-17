package work.art1st.mineRails.core.api.physics.rail

/** Immutable identity, lifecycle, and position of an operational rail object. */
data class RailObjectSnapshot(
    val ref: RailObjectRef,
    val lifecycleState: RailObjectLifecycleState,
    val position: RailPositionSnapshot
) {
    companion object {
        /** Capture a published [railObject] without retaining the live object. */
        fun capture(
            railObject: RailObject,
            facing: ViewDirection
        ): RailObjectSnapshot = RailObjectSnapshot(
            ref = railObject.ref,
            lifecycleState = railObject.lifecycleState,
            position = RailPositionSnapshot.capture(railObject.location, facing)
        )
    }
}
