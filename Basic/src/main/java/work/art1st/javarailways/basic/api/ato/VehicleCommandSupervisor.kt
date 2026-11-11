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

    fun isAcknowledgementRequired(train: Train): Boolean

    /** Rechecks safety on the physics owner before accepting an explicit player acknowledgement. */
    fun acknowledge(train: Train): Boolean
}
