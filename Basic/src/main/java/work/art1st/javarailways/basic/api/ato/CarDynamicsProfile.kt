package work.art1st.javarailways.basic.api.ato

import work.art1st.javarailways.basic.control.TrainDynamics

/**
 * First-party intrinsic car parameters, without independent third-party compatibility guarantees.
 * Configuration owners supply validated, stable values; controller bindings associate them with
 * exact car instances. This contract neither owns a live car nor changes its state or persistence.
 * @property mass physical mass in kg.
 * @property effectiveMass effective longitudinal mass in kg, including rotational inertia.
 * @property motorAdhesionMassRatio dimensionless fraction of mass contributing to powered adhesion.
 * @property brakeMaxForce maximum pneumatic brake force in N at unit demand.
 * @property brakeAdhesionMassRatio dimensionless fraction of mass contributing to braking adhesion.
 * @property electricBrakeMaxForce maximum electric brake force in N.
 * @property equipment immutable device coefficients; the default is the existing shared Basic equipment instance.
 * @property maximumMotorForceUpperBound proven unit-traction force ceiling in N over all speeds;
 * null retains the adhesion ceiling, without inferring a bound from sampled speeds.
 */
interface CarDynamicsProfile {
    val mass: Double
    val effectiveMass: Double
    val motorAdhesionMassRatio: Double
    val brakeMaxForce: Double
    val brakeAdhesionMassRatio: Double
    val electricBrakeMaxForce: Double get() = 0.0
    val equipment: VehicleDeviceEquipment get() = TrainDynamics.defaultEquipment
    /** Proven unit-traction force ceiling over all speeds; null retains the adhesion ceiling. */
    val maximumMotorForceUpperBound: Double? get() = null
    /**
     * @param speed speed in m/s.
     * @return unit-demand motor force in N before adhesion limiting.
     */
    fun motorForceAt(speed: Double): Double
    /**
     * @param speed speed in m/s.
     * @param defaultResistance train-default resistance function from m/s to N.
     * @return this car's resistance force in N.
     */
    fun resistanceForceAt(speed: Double, defaultResistance: (Double) -> Double): Double
}
