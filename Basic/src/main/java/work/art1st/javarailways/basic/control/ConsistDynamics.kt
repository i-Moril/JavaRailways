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

class PreparedDynamicsChange(
    val carProfiles: List<TrainDynamics.CarProfile>,
    val update: PreparedControllerStateUpdate,
    val serviceDecelerationMetersPerSecondSquared: Double? = null,
    val reactionTimeSeconds: Double? = null
)
