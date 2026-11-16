package work.art1st.javarailways.basic.api.ato

/**
 * Read-only equipment coefficients shared by first-party car profiles and captured capabilities;
 * this is not an independently supported third-party extension API. Implementations supply
 * immutable values: pressure in kPa, time in s, power in W, speed in m/s, force in N and rates in N/s.
 */
interface VehicleDeviceEquipment {
    /** Cylinder pressure corresponding to nominal pneumatic braking, in kPa. */
    val nominalCylinderPressureKpa: Double
    /** Delay before a changed effective command can act on the devices, in seconds. */
    val commandDelaySeconds: Double
    /** Maximum electric braking power, in W. */
    val electricBrakeMaxPowerWatts: Double
    /** Speed below which electric braking demand fades linearly, in m/s. */
    val electricBrakeFadeSpeedMetersPerSecond: Double
    /** Maximum electric braking force increase, in N/s. */
    val electricBrakeApplyRateNewtonsPerSecond: Double
    /** Maximum electric braking force decrease, in N/s. */
    val electricBrakeReleaseRateNewtonsPerSecond: Double
    /** Maximum traction force increase, in N/s. */
    val tractionApplyRateNewtonsPerSecond: Double
    /** Maximum traction force decrease, in N/s. */
    val tractionReleaseRateNewtonsPerSecond: Double
    /** Maximum cylinder pressure permitting traction demand, in kPa. */
    val tractionReleasePressureKpa: Double
    /** Maximum remaining electric braking force permitting traction demand, in N. */
    val electricReleaseForceThresholdNewtons: Double
}
