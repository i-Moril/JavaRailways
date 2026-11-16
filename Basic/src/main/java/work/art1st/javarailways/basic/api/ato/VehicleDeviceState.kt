package work.art1st.javarailways.basic.api.ato

/**
 * Immutable per-car device value shared by first-party physics and control modules; this is not
 * an independently supported third-party extension API or a persistence format.
 * Pressure is in kPa, forces in N, command age in seconds, and demands are dimensionless ratios.
 * [previousDemand] is signed (traction positive, brake negative); the separate previous ratios
 * and emergency flag retain the actual command used to detect a command change.
 * Construction and copying preserve the supplied values without validation or device evolution.
 */
data class VehicleDeviceState(
    /** Actual pneumatic cylinder pressure, in kPa. */
    val pressureKpa: Double = 0.0,
    /** Actual electric braking force, in N. */
    val electricBrakeForceNewtons: Double = 0.0,
    /** Actual traction force, in N, including force still decaying after a cut request. */
    val tractionForceNewtons: Double = 0.0,
    /** Device command age, in seconds; a changed effective command resets it to zero. */
    val commandAgeSeconds: Double = 0.0,
    /** Signed demand summary: negative for braking, otherwise positive for traction. */
    val previousDemand: Double = 0.0,
    /** Previous independent traction ratio used for command-change detection. */
    val previousTractionRatio: Double = previousDemand.coerceAtLeast(0.0),
    /** Previous independent braking ratio used for command-change detection. */
    val previousBrakeRatio: Double = (-previousDemand).coerceAtLeast(0.0),
    /** Previous emergency-brake request used for command-change detection. */
    val previousEmergencyBrake: Boolean = false
)
