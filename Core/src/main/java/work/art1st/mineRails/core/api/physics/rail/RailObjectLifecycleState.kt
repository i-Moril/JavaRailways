package work.art1st.mineRails.core.api.physics.rail

/** Published lifecycle state of an operational rail object. */
enum class RailObjectLifecycleState {
    /** The object is registered and may participate in perception. */
    ACTIVE,

    /** The object has been removed and its last location is diagnostic only. */
    REMOVED
}
