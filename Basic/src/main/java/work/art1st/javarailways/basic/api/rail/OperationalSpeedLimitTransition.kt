package work.art1st.javarailways.basic.api.rail

import work.art1st.javarailways.core.api.physics.rail.RailObject
import work.art1st.javarailways.core.api.physics.rail.ViewDirection

/**
 * Live infrastructure speed transition shared by first-party physical control consumers. The
 * original [RailObject] retains its world-scoped reference, location and lifecycle; this capability
 * does not wrap or register a second object. It carries the same native limit for every train,
 * without interpreting movement authority, signalling rules or controller policy.
 *
 * Read synchronously in the owning world's allowed simulation/physics context, at the caller's
 * existing observation or PASS-processing stage. Neither a type check nor [appliesTo] captures the
 * limit; [speedLimitMetersPerSecond] reads the live transition when accessed. Removed transitions
 * and direction filtering remain the caller/index's responsibility. No scheduling, persistent
 * data, resource grant or independent third-party compatibility promise is introduced.
 */
interface OperationalSpeedLimitTransition : RailObject {
    /** Resulting native cap in m/s, or null to release the preceding native transition cap. */
    val speedLimitMetersPerSecond: Double?

    /** Pure direction predicate: whether this transition applies in the physical travel direction. */
    fun appliesTo(direction: ViewDirection): Boolean
}
