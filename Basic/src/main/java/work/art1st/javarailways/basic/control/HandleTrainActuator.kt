package work.art1st.javarailways.basic.control

import work.art1st.javarailways.basic.api.ato.ActuationStep
import work.art1st.javarailways.basic.api.ato.ControlDemand
import work.art1st.javarailways.basic.api.ato.BrakingGradeSegment
import work.art1st.javarailways.basic.api.ato.GradeAwareBrakingEnvelope
import work.art1st.javarailways.basic.api.ato.TrainActuator
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.physics.train.Car
import work.art1st.javarailways.core.api.control.ControllerConsistState
import work.art1st.javarailways.core.api.control.PreparedControllerStateUpdate
import kotlin.math.abs

/** ATO actuator that selects configured handles and executes the same dynamics as manual control. */
class HandleTrainActuator(
    train: Train,
    trainProfile: TrainDynamics.TrainProfile,
    carProfiles: List<TrainDynamics.CarProfile>,
    gravityMetersPerSecondSquared: Double,
    initialSpeedMetersPerSecond: Double = train.speed,
    profileResolver: ((Car) -> TrainDynamics.CarProfile?)? = null
) : TrainActuator, ConsistDynamicsActuator {
    private val dynamics = TrainDynamicsExecutor(
        train,
        trainProfile,
        carProfiles,
        gravityMetersPerSecondSquared,
        initialSpeedMetersPerSecond,
        profileResolver
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

    override fun dynamicsProfileFor(car: Car): TrainDynamics.CarProfile? = dynamics.dynamicsProfileFor(car)

    override fun prepareDynamicsChange(cars: List<Car>, source: DynamicsProfileSource?): PreparedDynamicsChange {
        val prepared = dynamics.prepareConsistChange(cars, source)
        return PreparedDynamicsChange(
            prepared.carProfiles,
            prepared.update,
            TrainDynamics.flatServiceDeceleration(dynamics.trainProfile, prepared.carProfiles, dynamics.gravityMetersPerSecondSquared),
            serviceBrakeReactionTimeSeconds(dynamics.trainProfile, prepared.carProfiles, dynamics.gravityMetersPerSecondSquared)
        )
    }

    fun prepareConfigurationChange(
        trainProfile: TrainDynamics.TrainProfile,
        carProfiles: List<TrainDynamics.CarProfile>
    ): List<PreparedControllerStateUpdate> {
        val preparedHandles = prepareHandles(trainProfile)
        require(selectedHandle in preparedHandles.progressiveOrder || selectedHandle == preparedHandles.emergencyBrake) {
            "Updated configuration does not contain active automatic handle '$selectedHandle'"
        }
        return dynamics.prepareConfigurationChange(trainProfile, carProfiles) + handles.prepare(preparedHandles)
    }

    override fun step(
        currentSpeedMetersPerSecond: Double,
        demand: ControlDemand,
        deltaTimeSeconds: Double
    ): ActuationStep {
        require(currentSpeedMetersPerSecond.isFinite() && currentSpeedMetersPerSecond >= 0.0)
        require(deltaTimeSeconds.isFinite() && deltaTimeSeconds >= 0.0)
        val target = demand.targetSpeedMetersPerSecond.coerceAtMost(maximumSpeedMetersPerSecond)
        val desired = desiredHandle(currentSpeedMetersPerSecond, target, deltaTimeSeconds)
        val proposed = progressToward(selectedHandle, desired)
        selectedHandle = if (target >= currentSpeedMetersPerSecond && deltaTimeSeconds > 0.0 &&
            !canRecoverBelowTarget(currentSpeedMetersPerSecond, proposed, target, deltaTimeSeconds)) {
            // No proven safe acceleration: start the real configured braking sequence now.
            // A suddenly reduced limit or changed grade may already be physically unattainable;
            // never conceal that by changing the integrated speed or distance.
            progressToward(selectedHandle, serviceBrakeHandles.lastOrNull() ?: NEUTRAL_HANDLE)
        } else proposed
        val result = dynamics.step(currentSpeedMetersPerSecond, selectedHandle, deltaTimeSeconds)
        return ActuationStep(result.state.speed, result.distance)
    }

    override fun freeze() {
        selectedHandle = emergencyBrakeHandle ?: serviceBrakeHandles.lastOrNull() ?: NEUTRAL_HANDLE
    }

    override fun brakingEnvelope(segments: List<BrakingGradeSegment>): GradeAwareBrakingEnvelope =
        GradeAwareBrakingEnvelope.fromDynamics(
            dynamics.trainProfile,
            dynamics.carProfiles,
            dynamics.gravityMetersPerSecondSquared,
            segments,
            currentAccelerationMetersPerSecondSquared = dynamics.state.acceleration
        )

    /**
     * A one-tick safe speed is insufficient: handle travel and positive residual acceleration can
     * cross the limit later. Prove a bounded recovery using the same dynamics and current grades.
     * Exhausting the budget is inconclusive, so it never authorizes further traction.
     */
    private fun canRecoverBelowTarget(currentSpeed: Double, proposed: String, target: Double, delta: Double): Boolean {
        val preview = dynamics.snapshotPreview(delta)
        val braking = serviceBrakeHandles.lastOrNull() ?: NEUTRAL_HANDLE
        val brakeScale = dynamics.trainProfile.normalizedBrakeScale(braking)
        var handle = proposed
        var state = dynamics.state.copy(speed = currentSpeed)
        repeat(MAX_RECOVERY_PREVIEW_STEPS) {
            val result = preview(state, handle)
            state = result.state
            if (state.speed > target + SPEED_EPSILON) return false
            val netForce = result.tractionForce - result.brakeForce - result.resistanceForce - result.gradeForce
            if (handle == braking && state.acceleration <= 0.0 && state.brakeScale <= brakeScale &&
                netForce <= 0.0) return true
            handle = progressToward(handle, braking)
        }
        return false
    }

    private fun desiredHandle(currentSpeed: Double, targetSpeed: Double, deltaSeconds: Double): String {
        if (targetSpeed == 0.0 && currentSpeed > SPEED_EPSILON && serviceBrakeHandles.isNotEmpty()) {
            val serviceResult = dynamics.preview(currentSpeed, serviceBrakeHandles.last(), deltaSeconds)
            val emergency = emergencyBrakeHandle
            if (serviceResult.state.speed >= currentSpeed && emergency != null) {
                return emergency
            }
        }
        return when {
            targetSpeed > currentSpeed + SPEED_EPSILON -> {
                (listOf(NEUTRAL_HANDLE) + powerHandles)
                    .map { handle -> handle to dynamics.preview(currentSpeed, handle, deltaSeconds).state.speed }
                    .filter { (_, nextSpeed) -> nextSpeed <= targetSpeed + SPEED_EPSILON }
                    .minWithOrNull(
                        compareBy<Pair<String, Double>> { (_, nextSpeed) -> targetSpeed - nextSpeed }
                            .thenBy { (handle, _) -> handleStrength(handle) }
                    )?.first ?: NEUTRAL_HANDLE
            }
            targetSpeed < currentSpeed - SPEED_EPSILON -> {
                (listOf(NEUTRAL_HANDLE) + serviceBrakeHandles).firstOrNull { handle ->
                    dynamics.preview(currentSpeed, handle, deltaSeconds).state.speed <=
                        targetSpeed + SPEED_EPSILON
                } ?: serviceBrakeHandles.lastOrNull() ?: NEUTRAL_HANDLE
            }
            else -> (listOf(NEUTRAL_HANDLE) + powerHandles + serviceBrakeHandles)
                .map { handle -> handle to dynamics.preview(currentSpeed, handle, deltaSeconds).state.speed }
                .minWithOrNull(
                    compareBy<Pair<String, Double>> { (_, nextSpeed) -> abs(nextSpeed - targetSpeed) }
                        .thenBy { (handle, _) -> if (handle.equals(selectedHandle, ignoreCase = true)) 0 else 1 }
                        .thenBy { (handle, _) -> handleStrength(handle) }
                )?.first ?: NEUTRAL_HANDLE
        }
    }

    private fun progressToward(current: String, desired: String): String {
        if (desired.equals(EMERGENCY_BRAKE_HANDLE, ignoreCase = true)) return desired
        val currentIndex = progressiveOrder.indexOfFirst { it.equals(current, ignoreCase = true) }
            .takeIf { it >= 0 } ?: progressiveOrder.indexOf(NEUTRAL_HANDLE)
        val desiredIndex = progressiveOrder.indexOfFirst { it.equals(desired, ignoreCase = true) }
            .takeIf { it >= 0 } ?: progressiveOrder.indexOf(NEUTRAL_HANDLE)
        return when {
            currentIndex < desiredIndex -> progressiveOrder[currentIndex + 1]
            currentIndex > desiredIndex -> progressiveOrder[currentIndex - 1]
            else -> progressiveOrder[currentIndex]
        }
    }

    private fun handleStrength(handle: String): Double = when {
        handle == NEUTRAL_HANDLE -> 0.0
        handle in powerHandles -> dynamics.trainProfile.normalizedPowerScale(handle)
        else -> dynamics.trainProfile.normalizedBrakeScale(handle)
    }

    companion object {
        private const val NEUTRAL_HANDLE = "N"
        private const val EMERGENCY_BRAKE_HANDLE = "EB"
        private const val MAX_RECOVERY_PREVIEW_STEPS = 256
        private const val SPEED_EPSILON = 1.0e-6
        private const val NOMINAL_CONTROL_STEP_SECONDS = 0.05

        private fun prepareHandles(profile: TrainDynamics.TrainProfile): HandleConfiguration {
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
         * Conservative delay before the strongest service brake can be treated as fully available.
         * It covers progressive handle travel, brake application, and the acceleration ramp imposed
         * by the configured brake jerk. The factories use the same value in ATO stopping curves.
         */
        fun serviceBrakeReactionTimeSeconds(
            trainProfile: TrainDynamics.TrainProfile,
            carProfiles: List<TrainDynamics.CarProfile>,
            gravityMetersPerSecondSquared: Double,
            controlStepSeconds: Double = NOMINAL_CONTROL_STEP_SECONDS,
            planningMarginSeconds: Double = 0.1
        ): Double {
            require(controlStepSeconds.isFinite() && controlStepSeconds >= 0.0)
            require(planningMarginSeconds.isFinite() && planningMarginSeconds >= 0.0)
            val powerSteps = trainProfile.powerHandles.count {
                trainProfile.normalizedPowerScale(it) > 0.0
            }
            val brakeSteps = trainProfile.brakeHandles.count {
                !it.equals(EMERGENCY_BRAKE_HANDLE, ignoreCase = true) &&
                    trainProfile.normalizedBrakeScale(it) > 0.0
            }
            val serviceDeceleration = TrainDynamics.flatServiceDeceleration(
                trainProfile,
                carProfiles,
                gravityMetersPerSecondSquared
            )
            val jerkResponseSeconds = trainProfile.brakeJerk.takeIf { it > 0.0 }
                ?.let { serviceDeceleration / it }
                ?: 0.0
            return planningMarginSeconds +
                (powerSteps + brakeSteps) * controlStepSeconds +
                trainProfile.brakeApplyTime +
                jerkResponseSeconds
        }
    }
}
