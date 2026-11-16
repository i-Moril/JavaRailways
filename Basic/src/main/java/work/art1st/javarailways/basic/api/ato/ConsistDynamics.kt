package work.art1st.javarailways.basic.api.ato

import work.art1st.javarailways.core.api.control.PreparedControllerStateUpdate
import work.art1st.javarailways.core.api.physics.train.Car

/**
 * First-party exact-car parameter and device-state lookup, without independent third-party API
 * compatibility guarantees. Call on the owning simulation context with physics excluded when
 * preparing structural changes. Lookups do not install state or create a world registration.
 */
interface DynamicsProfileSource {
    /**
     * @param car exact instance to look up; matching IDs do not establish identity.
     * @return its intrinsic parameters, or null when this source has no binding for it.
     */
    fun dynamicsProfileFor(car: Car): CarDynamicsProfile?
    /**
     * @param car exact instance whose equipment state is requested.
     * @return detached equipment state, or null when unavailable; receiving Basic composition owns
     * explicit stopped/moving initialization for new controllerless cars.
     */
    fun dynamicsDeviceStateFor(car: Car): VehicleDeviceState? = null
}

/**
 * Optional first-party capability for actuators with consist-dependent intrinsic dynamics.
 * Preparation runs with physics excluded and must leave all live bindings and history unchanged;
 * Core installs opaque updates only after the complete structural operation has prepared successfully.
 */
interface ConsistDynamicsActuator : DynamicsProfileSource {
    /**
     * @param cars resulting consist in its required exact-instance order.
     * @param source optional counterpart supplying bindings for incoming cars.
     * @return complete uninstalled bindings plus derived braking limits; derivation may throw
     * without installing any state. Missing capability is handled by the caller, not a fallback here.
     */
    fun prepareDynamicsChange(cars: List<Car>, source: DynamicsProfileSource?): PreparedDynamicsChange
}

/**
 * Complete consist-dynamics preparation required by ATO consumers.
 *
 * Braking parameters are mandatory: an actuator that only produced profile/state preparation must
 * not present a partial "successful" result that consumers immediately have to reject.
 * Runtime-only preparation; consumers must keep the token list stable until Core installs it.
 * @property updates ordered Core-owned reference replacements, none installed by construction.
 * @property serviceDecelerationMetersPerSecondSquared finite positive derived service deceleration in m/s².
 * @property reactionTimeSeconds finite non-negative derived brake reaction time in seconds.
 */
class PreparedDynamicsChange(
    val updates: List<PreparedControllerStateUpdate>,
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
