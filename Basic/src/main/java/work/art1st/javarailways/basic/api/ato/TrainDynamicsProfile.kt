package work.art1st.javarailways.basic.api.ato

/**
 * First-party longitudinal train parameters, without independent third-party compatibility
 * guarantees. Configuration owners validate and supply stable values before controller preparation;
 * querying this interface neither changes a train nor installs state or defines persistence.
 * Force callbacks take speed in m/s and return N; handle scales are dimensionless physical demands.
 * @property maxSpeed maximum speed in m/s.
 * @property powerHandles available power handle labels in the supplied profile.
 * @property brakeHandles available brake handle labels, including any emergency handle.
 * @property brakeApplyTime pneumatic service-brake application time in seconds.
 * @property brakeReleaseTime pneumatic release time in seconds.
 * @property adhesionCoefficient dimensionless wheel/rail adhesion coefficient.
 * @property tractionJerk traction acceleration change limit in m/s³; zero disables the limit.
 * @property brakeJerk braking acceleration change limit in m/s³; zero disables the limit.
 * @property nominalOperatingAcceleration ordinary operating acceleration in m/s².
 * @property nominalOperatingDeceleration ordinary operating deceleration in m/s².
 */
interface TrainDynamicsProfile {
    val maxSpeed: Double
    val powerHandles: List<String>
    val brakeHandles: List<String>
    val brakeApplyTime: Double
    val brakeReleaseTime: Double
    val adhesionCoefficient: Double
    val tractionJerk: Double
    val brakeJerk: Double
    val nominalOperatingAcceleration: Double get() = 1.0
    val nominalOperatingDeceleration: Double get() = 1.0
    /**
     * @param handle supplied power handle label.
     * @return its dimensionless traction demand.
     */
    fun normalizedPowerScale(handle: String): Double
    /**
     * @param handle supplied brake handle label.
     * @return its dimensionless brake demand.
     */
    fun normalizedBrakeScale(handle: String): Double
    /**
     * @param speed speed in m/s.
     * @return train-default resistance force in N.
     */
    fun resistanceForceAt(speed: Double): Double
}
