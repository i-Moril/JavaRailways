package work.art1st.javarailways.basic.api.ato

import work.art1st.javarailways.core.api.control.PreparedControllerStateUpdate

/**
 * Optional first-party actuator capability for preparing configuration replacement, without an
 * independent third-party API compatibility promise. Call on the simulation owner with physics
 * excluded. All fallible derivation happens before Core installs the returned opaque updates;
 * preparation must preserve live bindings, handles and device/history state, including on failure.
 * This runtime-only capability does not load, persist or register configuration.
 */
interface ConfigurationDynamicsActuator {
    /**
     * @param trainProfile validated replacement train parameters.
     * @param carProfiles replacement parameters in current exact-car order.
     * @return ordered uninstalled actuator updates and derived braking values. The owning ATO
     * validates those values through its existing safety-profile construction before installation.
     */
    fun prepareConfigurationChange(
        trainProfile: TrainDynamicsProfile,
        carProfiles: List<CarDynamicsProfile>
    ): PreparedConfigurationDynamicsChange
}

/**
 * Configuration preparation without additional numeric validation, preserving the caller's existing
 * safety-profile validation order and failure semantics. This does not install or copy the token
 * list; its owner must keep it stable until Core installs the complete prepared operation.
 * @property updates ordered Core-owned uninstalled reference replacements.
 * @property serviceDecelerationMetersPerSecondSquared derived service deceleration in m/s²;
 * the owning ATO's safety-profile construction validates it before installation.
 * @property reactionTimeSeconds derived reaction time in seconds, validated by the same owner.
 */
class PreparedConfigurationDynamicsChange(
    val updates: List<PreparedControllerStateUpdate>,
    val serviceDecelerationMetersPerSecondSquared: Double,
    val reactionTimeSeconds: Double
)
