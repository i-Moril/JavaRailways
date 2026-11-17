package work.art1st.javarailways.basic.api.ato

import work.art1st.javarailways.core.api.physics.train.Train

/** World-local safety boundary. Implementations own their latches and must not advance dynamics. */
interface VehicleCommandSupervisor {
    fun supervise(
        train: Train,
        state: VehicleControlState,
        capabilities: VehicleControlCapabilities,
        command: VehicleControlCommand,
        deltaTimeSeconds: Double
    ): VehicleControlCommand

    /**
     * Applies a physical movement boundary after the same tick's dynamics step, in metres.
     * Physics-worker-only, nonblocking; returns at most [requestedDistanceMeters]. The default
     * leaves movement unchanged. A contact supervisor reuses the issuing world's exact contact
     * clamp and rejects stale/off-path authority rather than extending permission to move.
     */
    fun constrainMovementDistance(train: Train, requestedDistanceMeters: Double): Double = requestedDistanceMeters

    /**
     * Whether an exact stopped train has an owner-requested control hold. Controllers still
     * process commands and observations, then publish zero motion without integrating dynamics.
     * A moving train must brake through the ordinary supervised command path before this is true.
     */
    fun isMovementHeld(train: Train): Boolean = false

    fun isAcknowledgementRequired(train: Train): Boolean

    /** Rechecks safety on the physics owner before accepting an explicit player acknowledgement. */
    fun acknowledge(train: Train): Boolean
}
