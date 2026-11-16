package work.art1st.javarailways.basic.control

import work.art1st.javarailways.basic.api.ato.PreparedDynamicsChange
import work.art1st.javarailways.basic.api.ato.ConsistDynamicsActuator
import work.art1st.javarailways.basic.api.ato.DynamicsProfileSource
import work.art1st.javarailways.basic.api.ato.CarDynamicsProfile
import work.art1st.javarailways.basic.api.ato.TrainDynamicsProfile
import work.art1st.javarailways.basic.api.ato.VehicleDeviceState
import work.art1st.javarailways.basic.api.ato.ConfigurationDynamicsActuator
import work.art1st.javarailways.basic.api.ato.PreparedConfigurationDynamicsChange
import work.art1st.javarailways.basic.api.ato.ActuationStep
import work.art1st.javarailways.basic.api.ato.VehicleControlCommand
import work.art1st.javarailways.basic.api.ato.BrakingGradeSegment
import work.art1st.javarailways.basic.api.ato.GradeAwareBrakingEnvelope
import work.art1st.javarailways.basic.api.ato.TrainActuator
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.physics.train.Car
import work.art1st.javarailways.core.api.control.ControllerConsistState

/** Executes final physical control commands without selecting an automatic driving strategy. */
class HandleTrainActuator(
    train: Train,
    trainProfile: TrainDynamicsProfile,
    carProfiles: List<CarDynamicsProfile>,
    gravityMetersPerSecondSquared: Double,
    initialSpeedMetersPerSecond: Double = train.speed,
    profileResolver: ((Car) -> CarDynamicsProfile?)? = null,
    initialDeviceSource: DynamicsProfileSource? = null
) : TrainActuator, ConsistDynamicsActuator, ConfigurationDynamicsActuator {
    private val maximumControlDeltaSeconds = train.world.maximumPhysicsDeltaSeconds
    private val dynamics = TrainDynamicsExecutor(
        train,
        trainProfile,
        carProfiles,
        gravityMetersPerSecondSquared,
        initialSpeedMetersPerSecond,
        profileResolver,
        initialDeviceSource
    )
    private data class HandleConfiguration(
        val power: List<String>,
        val serviceBrake: List<String>,
        val emergencyBrake: String?
    ) {
        val progressiveOrder = serviceBrake.asReversed() + NEUTRAL_HANDLE + power
    }

    private val handles = ControllerConsistState(prepareHandles(trainProfile))
    private val powerHandles get() = handles.value.power
    private val serviceBrakeHandles get() = handles.value.serviceBrake
    private val emergencyBrakeHandle get() = handles.value.emergencyBrake
    private val progressiveOrder get() = handles.value.progressiveOrder

    override val maximumSpeedMetersPerSecond: Double get() = dynamics.trainProfile.maxSpeed
    val gravityMetersPerSecondSquared: Double get() = dynamics.gravityMetersPerSecondSquared

    @Volatile
    var selectedHandle: String = NEUTRAL_HANDLE
        private set

    val lastDynamicsResult: TrainDynamics.StepResult get() = dynamics.lastResult

    override fun dynamicsProfileFor(car: Car): CarDynamicsProfile? = dynamics.dynamicsProfileFor(car)
    override fun dynamicsDeviceStateFor(car: Car): VehicleDeviceState? = dynamics.dynamicsDeviceStateFor(car)

    override fun prepareDynamicsChange(cars: List<Car>, source: DynamicsProfileSource?): PreparedDynamicsChange {
        val prepared = dynamics.prepareConsistChange(cars, source)
        return PreparedDynamicsChange(
            listOf(prepared.update),
            TrainDynamics.flatServiceDeceleration(dynamics.trainProfile, prepared.carProfiles, dynamics.gravityMetersPerSecondSquared),
            serviceBrakeReactionTimeSeconds(dynamics.trainProfile, prepared.carProfiles, dynamics.gravityMetersPerSecondSquared)
        )
    }

    override fun prepareConfigurationChange(
        trainProfile: TrainDynamicsProfile,
        carProfiles: List<CarDynamicsProfile>
    ): PreparedConfigurationDynamicsChange {
        val preparedHandles = prepareHandles(trainProfile)
        require(selectedHandle == "CONTINUOUS" || selectedHandle in preparedHandles.progressiveOrder || selectedHandle == preparedHandles.emergencyBrake) {
            "Updated configuration does not contain active automatic handle '$selectedHandle'"
        }
        val updates = dynamics.prepareConfigurationChange(trainProfile, carProfiles) + handles.prepare(preparedHandles)
        return PreparedConfigurationDynamicsChange(
            updates,
            TrainDynamics.flatServiceDeceleration(trainProfile, carProfiles, gravityMetersPerSecondSquared),
            serviceBrakeReactionTimeSeconds(trainProfile, carProfiles, gravityMetersPerSecondSquared)
        )
    }

    override fun step(currentSpeedMetersPerSecond: Double, command: VehicleControlCommand,
        deltaTimeSeconds: Double): ActuationStep {
        require(currentSpeedMetersPerSecond.isFinite() && currentSpeedMetersPerSecond >= 0.0)
        require(deltaTimeSeconds.isFinite() && deltaTimeSeconds >= 0.0)
        val result = dynamics.step(currentSpeedMetersPerSecond, command, deltaTimeSeconds)
        selectedHandle = command.handle ?: if (command.emergencyBrake) "EB" else "CONTINUOUS"
        return ActuationStep(result.state.speed, result.distance)
    }

    override fun controlState(currentSpeedMetersPerSecond: Double) = dynamics.controlState(currentSpeedMetersPerSecond)

    override fun controlCapabilities(currentSpeedMetersPerSecond: Double) = dynamics.controlCapabilities(currentSpeedMetersPerSecond)

    override fun freeze() {
        selectedHandle = emergencyBrakeHandle ?: serviceBrakeHandles.lastOrNull() ?: NEUTRAL_HANDLE
    }

    override fun brakingEnvelope(segments: List<BrakingGradeSegment>, deltaTimeSeconds: Double,
        maximumControlDeltaSeconds: Double): GradeAwareBrakingEnvelope {
        require(segments.isNotEmpty())
        require(deltaTimeSeconds.isFinite() && deltaTimeSeconds >= 0.0)
        require(maximumControlDeltaSeconds.isFinite() && maximumControlDeltaSeconds > 0.0)
        require(maximumControlDeltaSeconds >= this.maximumControlDeltaSeconds && deltaTimeSeconds <= maximumControlDeltaSeconds)
        val currentGrade = dynamics.controlCapabilities(dynamics.state.speed).worstCurrentGrade
        val worstGrade = minOf(currentGrade, segments.minOf { it.grade })
        // This is the existing planner's analytic work envelope, not ATP admission. It must not
        // invoke ServiceBrakingResponse or any future dynamics trial from the control hot path.
        return GradeAwareBrakingEnvelope.fromDynamics(dynamics.trainProfile, dynamics.carProfiles,
            dynamics.gravityMetersPerSecondSquared, segments.map { it.copy(grade = worstGrade) },
            currentAccelerationMetersPerSecondSquared = dynamics.state.acceleration)
    }

    companion object {
        private const val NEUTRAL_HANDLE = "N"
        private const val EMERGENCY_BRAKE_HANDLE = "EB"
        private const val NOMINAL_CONTROL_STEP_SECONDS = 0.05

        private fun prepareHandles(profile: TrainDynamicsProfile): HandleConfiguration {
            require(profile.maxSpeed.isFinite() && profile.maxSpeed >= 0.0)
            return HandleConfiguration(
                profile.powerHandles.filter { profile.normalizedPowerScale(it) > 0.0 }
                    .sortedBy(profile::normalizedPowerScale),
                profile.brakeHandles.filterNot { it.equals(EMERGENCY_BRAKE_HANDLE, ignoreCase = true) }
                    .filter { profile.normalizedBrakeScale(it) > 0.0 }
                    .sortedBy(profile::normalizedBrakeScale),
                profile.brakeHandles.firstOrNull { it.equals(EMERGENCY_BRAKE_HANDLE, ignoreCase = true) }
            )
        }

        /**
         * Coarse factory horizon for immediate numeric service demand: current-interval latency,
         * actual command delay, traction/electric release, air establishment and jerk unwind.
         * ATP uses exact immutable devices; this static horizon cannot credit established force.
         */
        fun serviceBrakeReactionTimeSeconds(
            trainProfile: TrainDynamicsProfile,
            carProfiles: List<CarDynamicsProfile>,
            gravityMetersPerSecondSquared: Double,
            controlStepSeconds: Double = NOMINAL_CONTROL_STEP_SECONDS,
            planningMarginSeconds: Double = 0.1
        ): Double {
            require(controlStepSeconds.isFinite() && controlStepSeconds >= 0.0)
            require(planningMarginSeconds.isFinite() && planningMarginSeconds >= 0.0)
            val serviceDeceleration = TrainDynamics.flatServiceDeceleration(
                trainProfile,
                carProfiles,
                gravityMetersPerSecondSquared
            )
            val maximumTractionAcceleration = carProfiles.sumOf {
                it.mass * it.motorAdhesionMassRatio * gravityMetersPerSecondSquared * trainProfile.adhesionCoefficient
            } / TrainDynamics.totalEffectiveMass(carProfiles)
            val jerkResponseSeconds = trainProfile.brakeJerk.takeIf { it > 0.0 }
                ?.let { (maximumTractionAcceleration + serviceDeceleration) / it } ?: 0.0
            val commandDelay = carProfiles.maxOfOrNull { it.equipment.commandDelaySeconds } ?: 0.0
            val cutout = carProfiles.maxOfOrNull { car ->
                (car.mass * car.motorAdhesionMassRatio * gravityMetersPerSecondSquared * trainProfile.adhesionCoefficient) /
                    car.equipment.tractionReleaseRateNewtonsPerSecond
            } ?: 0.0
            val electricRelease = carProfiles.maxOfOrNull {
                it.electricBrakeMaxForce / it.equipment.electricBrakeReleaseRateNewtonsPerSecond
            } ?: 0.0
            val serviceScale = trainProfile.normalizedBrakeScale(TrainDynamics.strongestServiceBrakeHandle(trainProfile))
            return planningMarginSeconds + controlStepSeconds + commandDelay + cutout + electricRelease +
                trainProfile.brakeApplyTime.coerceAtLeast(0.0) * serviceScale + jerkResponseSeconds
        }
    }
}
