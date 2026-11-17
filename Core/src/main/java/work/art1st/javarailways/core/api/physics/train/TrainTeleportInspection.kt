package work.art1st.javarailways.core.api.physics.train

import work.art1st.javarailways.core.api.physics.PhysicsMaintenance
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot

/** Result of a read-only, owner-local rigid placement solve; no cross-train occupancy policy. */
sealed interface TrainTeleportInspection {
    /** A complete placement computed without changing this train or any turnout. */
    class Ready(val placement: PreparedTrainTeleport) : TrainTeleportInspection
    /** The selected physical path cannot support the requested complete rigid placement. */
    class Rejected(val reason: String) : TrainTeleportInspection
}

/**
 * Precomputed placement for one exact current train in its issuing [PhysicsMaintenance] scope.
 * Read-only geometry is expressed as values, in canonical AB metres. The opaque implementation
 * retains the solved cars and bogies; it is not persistent or a transferable thread capability.
 * Only that active owner scope may inspect or consume it, once, through [PhysicsMaintenance.teleport].
 */
interface PreparedTrainTeleport {
    val trainId: String
    val headLocation: RailPositionSnapshot
    val tailLocation: RailPositionSnapshot
    val occupiedRailIntervals: List<RailInterval>
}
