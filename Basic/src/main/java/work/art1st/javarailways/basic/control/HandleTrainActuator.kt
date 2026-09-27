package work.art1st.javarailways.basic.control

import work.art1st.javarailways.basic.api.ato.ActuationStep
import work.art1st.javarailways.basic.api.ato.ControlDemand
import work.art1st.javarailways.basic.api.ato.BrakingGradeSegment
import work.art1st.javarailways.basic.api.ato.GradeAwareBrakingEnvelope
import work.art1st.javarailways.basic.api.ato.TrainActuator
import work.art1st.javarailways.core.api.physics.train.Train
import kotlin.math.abs

/** ATO actuator that selects configured handles and executes the same dynamics as manual control. */
class HandleTrainActuator(
    train: Train,
    trainProfile: TrainDynamics.TrainProfile,
    carProfiles: List<TrainDynamics.CarProfile>,
    gravityMetersPerSecondSquared: Double,
    initialSpeedMetersPerSecond: Double = train.speed
) : TrainActuator {
    private val dynamics = TrainDynamicsExecutor(
        train,
        trainProfile,
        carProfiles,
        gravityMetersPerSecondSquared,
        initialSpeedMetersPerSecond
    )
    private val powerHandles = trainProfile.powerHandles
        .filter { trainProfile.normalizedPowerScale(it) > 0.0 }
        .sortedBy(trainProfile::normalizedPowerScale)
    private val serviceBrakeHandles = trainProfile.brakeHandles
        .filterNot { it.equals(EMERGENCY_BRAKE_HANDLE, ignoreCase = true) }
        .filter { trainProfile.normalizedBrakeScale(it) > 0.0 }
        .sortedBy(trainProfile::normalizedBrakeScale)
    private val emergencyBrakeHandle = trainProfile.brakeHandles
        .firstOrNull { it.equals(EMERGENCY_BRAKE_HANDLE, ignoreCase = true) }
    private val progressiveOrder = serviceBrakeHandles.asReversed() + NEUTRAL_HANDLE + powerHandles

    override val maximumSpeedMetersPerSecond: Double = trainProfile.maxSpeed

    @Volatile
    var selectedHandle: String = NEUTRAL_HANDLE
        private set

    val lastDynamicsResult: TrainDynamics.StepResult get() = dynamics.lastResult

    init {
        require(maximumSpeedMetersPerSecond.isFinite() && maximumSpeedMetersPerSecond > 0.0)
        require(progressiveOrder.isNotEmpty())
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
        selectedHandle = progressToward(selectedHandle, desired)
        val result = dynamics.step(currentSpeedMetersPerSecond, selectedHandle, deltaTimeSeconds)
        return ActuationStep(result.state.speed, result.distance)
    }

    override fun freeze(): ActuationStep {
        selectedHandle = emergencyBrakeHandle ?: serviceBrakeHandles.lastOrNull() ?: NEUTRAL_HANDLE
        return ActuationStep(0.0, 0.0)
    }

    override fun brakingEnvelope(segments: List<BrakingGradeSegment>): GradeAwareBrakingEnvelope? =
        segments.takeIf { it.isNotEmpty() }?.let {
            GradeAwareBrakingEnvelope.fromDynamics(
                dynamics.trainProfile,
                dynamics.carProfiles,
                dynamics.gravityMetersPerSecondSquared,
                it
            )
        }

    private fun desiredHandle(currentSpeed: Double, targetSpeed: Double, deltaSeconds: Double): String {
        if (targetSpeed == 0.0 && currentSpeed > SPEED_EPSILON && serviceBrakeHandles.isNotEmpty()) {
            val serviceResult = dynamics.preview(currentSpeed, serviceBrakeHandles.last(), deltaSeconds)
            if (serviceResult.state.speed >= currentSpeed && emergencyBrakeHandle != null) {
                return emergencyBrakeHandle
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
                .minWithOrNull(
                    compareBy<String> {
                        abs(dynamics.preview(currentSpeed, it, deltaSeconds).state.speed - targetSpeed)
                    }.thenBy { if (it.equals(selectedHandle, ignoreCase = true)) 0 else 1 }
                        .thenBy(::handleStrength)
                ) ?: NEUTRAL_HANDLE
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
        private const val SPEED_EPSILON = 1.0e-6
        private const val NOMINAL_CONTROL_STEP_SECONDS = 0.05

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
                trainProfile.brakeApplyTime.coerceAtLeast(0.0) +
                jerkResponseSeconds
        }
    }
}
