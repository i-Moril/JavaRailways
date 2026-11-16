package work.art1st.javarailways.simpleato.testing

import work.art1st.javarailways.basic.api.ato.ActuationStep
import work.art1st.javarailways.basic.api.ato.BrakingGradeSegment
import work.art1st.javarailways.basic.api.ato.ControlDemand
import work.art1st.javarailways.basic.api.ato.GradeAwareBrakingEnvelope
import work.art1st.javarailways.basic.api.ato.TrainActuator
import work.art1st.javarailways.basic.api.ato.*
import work.art1st.javarailways.basic.control.TrainDynamics
import work.art1st.javarailways.basic.control.VehicleControlCapabilityModel
import work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile

/** Constant-acceleration actuator for small, deterministic planner and constraint tests. */
class KinematicTrainActuator(
    override val maximumSpeedMetersPerSecond: Double,
    private val profile: AtoSafetyProfile
) : TrainActuator {
    init {
        require(maximumSpeedMetersPerSecond > 0.0 && maximumSpeedMetersPerSecond.isFinite())
    }

    fun step(
        currentSpeedMetersPerSecond: Double,
        demand: ControlDemand,
        deltaTimeSeconds: Double
    ): ActuationStep {
        require(currentSpeedMetersPerSecond >= 0.0 && currentSpeedMetersPerSecond.isFinite())
        require(deltaTimeSeconds >= 0.0 && deltaTimeSeconds.isFinite())

        val target = demand.targetSpeedMetersPerSecond.coerceAtMost(maximumSpeedMetersPerSecond)
        val nextSpeed = when {
            currentSpeedMetersPerSecond < target -> minOf(
                target,
                currentSpeedMetersPerSecond + profile.accelerationMetersPerSecondSquared * deltaTimeSeconds
            )
            currentSpeedMetersPerSecond > target -> maxOf(
                target,
                currentSpeedMetersPerSecond - profile.serviceDecelerationMetersPerSecondSquared * deltaTimeSeconds
            )
            else -> target
        }
        return ActuationStep(
            speedMetersPerSecond = nextSpeed,
            distanceMeters = (currentSpeedMetersPerSecond + nextSpeed) * 0.5 * deltaTimeSeconds
        )
    }

    // Ideal, immediate equipment is deliberate here: this fixture tests world/planner plumbing.
    // Real equipment and frozen S100 acceptance are tested separately.
    private val trainProfile = object : TrainDynamicsProfile {
        override val maxSpeed = maximumSpeedMetersPerSecond
        override val powerHandles = listOf("P")
        override val brakeHandles = listOf("B", "EB")
        override val brakeApplyTime = 0.0
        override val brakeReleaseTime = 0.0
        override val adhesionCoefficient = 10.0
        override val tractionJerk = 0.0
        override val brakeJerk = 0.0
        override fun normalizedPowerScale(handle: String) = if (handle == "P") 1.0 else 0.0
        override fun normalizedBrakeScale(handle: String) = if (handle == "B" || handle == "EB") 1.0 else 0.0
        override fun resistanceForceAt(speed: Double) = 0.0
    }
    private val carProfile = object : CarDynamicsProfile {
        override val mass = 1.0
        override val effectiveMass = 1.0
        override val motorAdhesionMassRatio = 1.0
        override val brakeAdhesionMassRatio = 1.0
        override val brakeMaxForce = profile.serviceDecelerationMetersPerSecondSquared
        override val maximumMotorForceUpperBound = profile.accelerationMetersPerSecondSquared
        override val equipment = TrainDynamics.Equipment(commandDelaySeconds = 0.0,
            tractionApplyRateNewtonsPerSecond = 1e10, tractionReleaseRateNewtonsPerSecond = 1e10)
        override fun motorForceAt(speed: Double) = profile.accelerationMetersPerSecondSquared
        override fun resistanceForceAt(speed: Double, defaultResistance: (Double) -> Double) = 0.0
    }
    private val carInputs = listOf(TrainDynamics.CarInput(carProfile, 0.0))
    private val model = VehicleControlCapabilityModel(trainProfile, listOf(carProfile), 9.80665)
    private var state = TrainDynamics.initialState(listOf(carProfile), brakeScale = 0.0)
    private var command = VehicleControlCommand.COAST
    override fun controlState(currentSpeedMetersPerSecond: Double) = VehicleControlState(
        currentSpeedMetersPerSecond, state.acceleration, state.brakeScale, command, state.devices.toList())
    override fun controlCapabilities(currentSpeedMetersPerSecond: Double) = model.snapshot(currentSpeedMetersPerSecond, carInputs)
    override fun step(currentSpeedMetersPerSecond: Double, command: VehicleControlCommand,
        deltaTimeSeconds: Double): ActuationStep {
        this.command = command
        val result = TrainDynamics.step(state.copy(speed = currentSpeedMetersPerSecond), trainProfile,
            carInputs, command, 9.80665, deltaTimeSeconds)
        state = result.state
        return ActuationStep(result.state.speed, result.distance)
    }

    override fun brakingEnvelope(
        segments: List<BrakingGradeSegment>,
        deltaTimeSeconds: Double,
        maximumControlDeltaSeconds: Double
    ): GradeAwareBrakingEnvelope =
        GradeAwareBrakingEnvelope.constant(profile.serviceDecelerationMetersPerSecondSquared, segments)
}
