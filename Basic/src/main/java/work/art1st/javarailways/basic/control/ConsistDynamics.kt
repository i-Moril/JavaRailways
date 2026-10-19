package work.art1st.javarailways.basic.control

import work.art1st.javarailways.core.api.control.PreparedControllerStateUpdate
import work.art1st.javarailways.core.api.physics.train.Car

/** First-party control composition seam, not a Basic add-on API or a world registry. */
interface DynamicsProfileSource {
    /** Exact car-instance lookup; null means this source has no binding for that car. */
    fun dynamicsProfileFor(car: Car): TrainDynamics.CarProfile?
}

/** Optional capability for actuators with consist-dependent intrinsic dynamics. */
interface ConsistDynamicsActuator : DynamicsProfileSource {
    fun prepareDynamicsChange(cars: List<Car>, source: DynamicsProfileSource?): PreparedDynamicsChange
}

/** Exact profile/state preparation shared by the executor and actuator composition. */
internal class PreparedConsistDynamics(
    val carProfiles: List<TrainDynamics.CarProfile>,
    val update: PreparedControllerStateUpdate
)

/**
 * Complete consist-dynamics preparation required by ATO consumers.
 *
 * Braking parameters are mandatory: an actuator that only produced profile/state preparation must
 * not present a partial "successful" result that consumers immediately have to reject.
 */
class PreparedDynamicsChange(
    val carProfiles: List<TrainDynamics.CarProfile>,
    val update: PreparedControllerStateUpdate,
    val serviceDecelerationMetersPerSecondSquared: Double,
    val reactionTimeSeconds: Double
) {
    init {
        require(
            serviceDecelerationMetersPerSecondSquared.isFinite() &&
                serviceDecelerationMetersPerSecondSquared > 0.0
        ) { "serviceDecelerationMetersPerSecondSquared must be finite and positive" }
        require(reactionTimeSeconds.isFinite() && reactionTimeSeconds >= 0.0) {
            "reactionTimeSeconds must be finite and non-negative"
        }
    }
}
