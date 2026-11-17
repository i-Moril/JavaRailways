package work.art1st.javarailways.core.api.physics

import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.train.PreparedTrainTeleport
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.physics.train.TrainTeleportInspection

/**
 * Owner-local maintenance while this world's physical frames are suspended.
 *
 * Issued only inside [World.withPhysicsStalled]; every operation is synchronous and nonblocking
 * after the scope has entered. The live capability and its prepared placements expire when that
 * callback returns and cannot be used from another thread or world. Physical epochs cannot advance
 * during the callback. Successful changes publish together at its end using the completed epoch.
 */
interface PhysicsMaintenance {
    /** This exact live world. Reading also requires the issuing maintenance scope to remain active. */
    val world: World

    /**
     * Solves a single exact registered train's complete rigid placement on the currently selected
     * physical path. [target] is its front head, with canonical AB arc length in metres; as with
     * explicit placement, the result has forward consist order. No live geometry, motion, direction,
     * publication or turnout changes. Rejection describes unplaceable geometry, not occupancy policy:
     * the caller compares the returned occupied intervals with other trains and other placements.
     */
    fun inspectTeleport(train: Train, target: RailPositionSnapshot): TrainTeleportInspection

    /**
     * Installs this scope's exact, unused precomputed single-train placement without solving again.
     * Clears physical motion and derailment, retaining controller input. It creates no movement
     * traversal. Publication is deferred until the outer maintenance callback succeeds; before
     * publication, a failed scope restores its own train changes exactly. Foreign, stale, expired
     * and already used placements fail before installation. A publication failure keeps physics
     * faulted and is not rolled back after observers may have seen the new state.
     */
    fun teleport(placement: PreparedTrainTeleport)
}
