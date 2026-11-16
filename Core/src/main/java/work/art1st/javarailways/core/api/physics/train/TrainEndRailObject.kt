package work.art1st.javarailways.core.api.physics.train

import work.art1st.javarailways.core.api.physics.rail.RailObject

/**
 * Live train-end identity for first-party physical observation consumers. Independent third-party
 * API compatibility is not promised. This capability is implemented by the original rail object,
 * not an observation wrapper; use its inherited reference and lifecycle for object identity.
 * Read in the world's allowed simulation/physics context, with currentness owned by the caller.
 * It is not an immutable snapshot and must not be cached as proof of a later owner's availability.
 *
 * @property trainOrNull current owning train, or null after this end's lifecycle becomes REMOVED.
 * Each access reads the live lifecycle; observation does not capture or prolong ownership.
 * @property isHead whether this object has the travel-direction HEAD role rather than TAIL.
 * This is not the persistent physical A/B end side; reversing a train's facing does not turn this
 * object's role into a physical-side identity.
 */
interface TrainEndRailObject : RailObject {
    /** Live owning train, or null after REMOVED; each access reads the current lifecycle. */
    val trainOrNull: Train?
    /** Whether this is the travel-direction HEAD rather than TAIL, not the persistent physical A/B side. */
    val isHead: Boolean
}
