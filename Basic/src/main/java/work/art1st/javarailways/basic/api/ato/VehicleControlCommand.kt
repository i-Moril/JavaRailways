package work.art1st.javarailways.basic.api.ato

/**
 * Physical demands, independent of the driver's handle arrangement.
 * Ratios use the profile's force scale and may exceed one (for example a configured EB scale).
 * Emergency braking requests traction cut and selects the profile's emergency brake strength;
 * actual forces still follow per-car delays, release/build rates and interlocks.
 * [handle] is a diagnostic label and does not determine physical forces.
 */
data class VehicleControlCommand(
    val tractionRatio: Double = 0.0,
    val brakeRatio: Double = 0.0,
    val emergencyBrake: Boolean = false,
    val handle: String? = null
) {
    init {
        require(tractionRatio.isFinite() && tractionRatio >= 0.0)
        require(brakeRatio.isFinite() && brakeRatio >= 0.0)
    }

    companion object {
        val COAST = VehicleControlCommand(handle = "N")
        val EMERGENCY_BRAKE = VehicleControlCommand(brakeRatio = 1.0, emergencyBrake = true, handle = "EB")
    }
}

data class VehicleControlState(
    val speedMetersPerSecond: Double,
    val accelerationMetersPerSecondSquared: Double,
    val actualBrakeRatio: Double,
    val lastCommand: VehicleControlCommand,
    val devices: List<work.art1st.javarailways.basic.control.TrainDynamics.CarDeviceState> = emptyList()
)

data class VehicleHandleCapability(
    val handle: String,
    val command: VehicleControlCommand,
    val steadyAccelerationMetersPerSecondSquared: Double
)

data class VehiclePerformanceSample(
    val speedMetersPerSecond: Double,
    val maximumTractionAccelerationMetersPerSecondSquared: Double,
    val serviceDecelerationMetersPerSecondSquared: Double,
    val emergencyDecelerationMetersPerSecondSquared: Double
)

/** Snapshot captured on the physics worker, without speculative dynamics integration. */
data class VehicleControlCapabilities(
    val configurationIdentity: Any,
    val maximumSpeedMetersPerSecond: Double,
    val nominalAccelerationMetersPerSecondSquared: Double,
    val nominalDecelerationMetersPerSecondSquared: Double,
    val maximumTractionAccelerationMetersPerSecondSquared: Double,
    val flatServiceDecelerationMetersPerSecondSquared: Double,
    val flatEmergencyDecelerationMetersPerSecondSquared: Double,
    val maximumServiceBrakeRatio: Double,
    val emergencyBrakeRatio: Double,
    val brakeApplyTimeSeconds: Double,
    val brakeReleaseTimeSeconds: Double,
    val brakeJerkMetersPerSecondCubed: Double,
    val tractionJerkMetersPerSecondCubed: Double,
    val gravityMetersPerSecondSquared: Double,
    val massToEffectiveMassRatio: Double,
    val worstCurrentGrade: Double,
    val performanceSamples: List<VehiclePerformanceSample>,
    val handles: List<VehicleHandleCapability>,
    val deviceCapabilities: List<VehicleCarControlCapabilities> = emptyList()
)

/** Immutable coefficients for one actual car, ordered exactly as VehicleControlState.devices. */
data class VehicleCarControlCapabilities(
    val massKilograms: Double,
    val effectiveMassKilograms: Double,
    val brakeMaxForceNewtons: Double,
    val electricBrakeMaxForceNewtons: Double,
    val motorAdhesionForceNewtons: Double,
    val brakeAdhesionForceNewtons: Double,
    override val nominalCylinderPressureKpa: Double,
    override val commandDelaySeconds: Double,
    val airApplyRateKpaPerSecond: Double,
    val airReleaseRateKpaPerSecond: Double,
    override val electricBrakeMaxPowerWatts: Double,
    override val electricBrakeFadeSpeedMetersPerSecond: Double,
    override val electricBrakeApplyRateNewtonsPerSecond: Double,
    override val electricBrakeReleaseRateNewtonsPerSecond: Double,
    override val tractionApplyRateNewtonsPerSecond: Double,
    override val tractionReleaseRateNewtonsPerSecond: Double,
    override val tractionReleasePressureKpa: Double,
    override val electricReleaseForceThresholdNewtons: Double,
    val motorForceAtCurrentSpeedNewtons: Double
) : work.art1st.javarailways.basic.control.VehicleDeviceEquipment
