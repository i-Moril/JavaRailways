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
    private val maximumControlDeltaSeconds = train.world.maximumPhysicsDeltaSeconds
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
    override fun dynamicsDeviceStateFor(car: Car): TrainDynamics.CarDeviceState? = dynamics.dynamicsDeviceStateFor(car)

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
        val preview = dynamics.snapshotPreview(currentSpeedMetersPerSecond, deltaTimeSeconds)
        val desired = desiredHandle(currentSpeedMetersPerSecond, target, preview)
        val proposed = progressToward(selectedHandle, desired)
        val worstRouteGrade = demand.brakingConstraints?.segments?.minOf { it.grade }
        selectedHandle = if (deltaTimeSeconds > 0.0 &&
            ((target >= currentSpeedMetersPerSecond && !canRecoverBelowTarget(proposed, target,
                currentSpeedMetersPerSecond, deltaTimeSeconds, worstRouteGrade)) ||
                !satisfiesBrakingLimits(proposed, demand, currentSpeedMetersPerSecond, deltaTimeSeconds))) {
            // No proven safe acceleration: start the real configured braking sequence now.
            // A suddenly reduced limit or changed grade may already be physically unattainable.
            // Never conceal that by changing the integrated speed or distance.
            progressToward(selectedHandle, serviceBrakeHandles.lastOrNull() ?: NEUTRAL_HANDLE)
        } else proposed
        val result = dynamics.step(currentSpeedMetersPerSecond, selectedHandle, deltaTimeSeconds)
        return ActuationStep(result.state.speed, result.distance)
    }

    private fun satisfiesBrakingLimits(handle: String, demand: ControlDemand, currentSpeed: Double, deltaSeconds: Double): Boolean {
        val constraints = demand.brakingConstraints ?: return true
        require(constraints.maximumControlDeltaSeconds >= maximumControlDeltaSeconds)
        val envelope = envelopeForHandle(constraints.segments, deltaSeconds,
            constraints.maximumControlDeltaSeconds, handle, currentSpeed)
        return constraints.limits.all { envelope.admitsSpeed(currentSpeed, it) }
    }

    override fun freeze() {
        selectedHandle = emergencyBrakeHandle ?: serviceBrakeHandles.lastOrNull() ?: NEUTRAL_HANDLE
    }

    override fun brakingEnvelope(
        segments: List<BrakingGradeSegment>,
        deltaTimeSeconds: Double,
        maximumControlDeltaSeconds: Double
    ): GradeAwareBrakingEnvelope {
        require(segments.isNotEmpty())
        return envelopeForHandle(segments, deltaTimeSeconds, maximumControlDeltaSeconds,
            progressToward(selectedHandle, serviceBrakeHandles.lastOrNull() ?: NEUTRAL_HANDLE))
    }

    private fun envelopeForHandle(
        segments: List<BrakingGradeSegment>,
        deltaTimeSeconds: Double,
        maximumControlDeltaSeconds: Double,
        firstHandle: String,
        currentSpeed: Double = dynamics.state.speed
    ): GradeAwareBrakingEnvelope {
        require(deltaTimeSeconds.isFinite() && deltaTimeSeconds >= 0.0)
        require(maximumControlDeltaSeconds.isFinite() && maximumControlDeltaSeconds > 0.0)
        require(maximumControlDeltaSeconds >= this.maximumControlDeltaSeconds)
        require(deltaTimeSeconds <= maximumControlDeltaSeconds)
        val snapshot = dynamics.state
        val preview = dynamics.snapshotPreview(currentSpeed, deltaTimeSeconds, segments.minOf { it.grade })
        val braking = serviceBrakeHandles.lastOrNull() ?: NEUTRAL_HANDLE
        val response = ServiceBrakingResponse.bound(preview, firstHandle, brakingSequence(firstHandle, braking),
            maximumControlDeltaSeconds)
        // The head entering an uphill slice does not move the tail off its prior downhill slope.
        // Without a per-car route projection, use the common worst observed/current grade for
        // steady braking too, rather than attributing the head's uphill work to the entire mass.
        val worstGrade = preview.cars.minOf { it.grade }
        val conservativeSegments = segments.map { it.copy(grade = worstGrade) }
        return GradeAwareBrakingEnvelope.fromDynamics(
            dynamics.trainProfile,
            dynamics.carProfiles,
            dynamics.gravityMetersPerSecondSquared,
            conservativeSegments,
            snapshot.acceleration,
            response = response
        )
    }

    /**
     * A one-tick safe speed is insufficient: handle travel and positive residual acceleration can
     * cross the limit later. Prove a bounded recovery using the same dynamics and current grades.
     * Exhausting the budget is inconclusive, so it never authorizes further traction.
     */
    private fun canRecoverBelowTarget(
        proposed: String,
        target: Double,
        currentSpeed: Double,
        deltaSeconds: Double,
        worstRouteGrade: Double?
    ): Boolean {
        require(deltaSeconds <= maximumControlDeltaSeconds)
        val braking = serviceBrakeHandles.lastOrNull() ?: NEUTRAL_HANDLE
        val preview = dynamics.snapshotPreview(currentSpeed, deltaSeconds, worstRouteGrade)
        val response = ServiceBrakingResponse.bound(preview, proposed, brakingSequence(proposed, braking),
            maximumControlDeltaSeconds)
        return response.proven && currentSpeed + response.speedGainMetersPerSecond <= target + SPEED_EPSILON
    }

    private fun brakingSequence(firstHandle: String, brakingHandle: String): List<String> {
        val subsequent = mutableListOf<String>()
        var handle = firstHandle
        do {
            handle = progressToward(handle, brakingHandle)
            subsequent += handle
        } while (handle != brakingHandle)
        return subsequent
    }

    private fun desiredHandle(currentSpeed: Double, targetSpeed: Double, preview: TrainDynamics.PreviewSession): String {
        if (targetSpeed == 0.0 && currentSpeed <= SPEED_EPSILON)
            return serviceBrakeHandles.lastOrNull() ?: NEUTRAL_HANDLE
        if (targetSpeed == 0.0 && currentSpeed > SPEED_EPSILON && serviceBrakeHandles.isNotEmpty()) {
            val serviceResult = preview.trial(serviceBrakeHandles.last())
            val emergency = emergencyBrakeHandle
            if (serviceResult.state.motion.speed >= currentSpeed && emergency != null) {
                return emergency
            }
        }
        return when {
            targetSpeed > currentSpeed + SPEED_EPSILON -> {
                val candidates = (listOf(NEUTRAL_HANDLE) + powerHandles).map { it to preview.trial(it).state.motion.speed }
                val feasible = candidates.filter { it.second <= targetSpeed + SPEED_EPSILON }
                val selected = feasible.minWithOrNull(compareBy<Pair<String, Double>> { targetSpeed - it.second }
                    .thenBy { handleStrength(it.first) })?.first ?: NEUTRAL_HANDLE
                // A delayed power command has no first-tick speed benefit. Keep a real power
                // intention until devices respond. The separate recovery proof still gates it.
                if (selected == NEUTRAL_HANDLE && feasible.any { it.first in powerHandles })
                    powerHandles.lastOrNull { handle -> feasible.any { it.first == handle } } ?: selected
                else selected
            }
            targetSpeed < currentSpeed - SPEED_EPSILON -> {
                (listOf(NEUTRAL_HANDLE) + serviceBrakeHandles).firstOrNull { handle -> preview.trial(handle).state.motion.speed <= targetSpeed + SPEED_EPSILON
                } ?: serviceBrakeHandles.lastOrNull() ?: NEUTRAL_HANDLE
            }
            else -> (listOf(NEUTRAL_HANDLE) + powerHandles + serviceBrakeHandles)
                .map { handle -> handle to preview.trial(handle).state.motion.speed }
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
            val commandDelay = carProfiles.maxOfOrNull { it.equipment.commandDelaySeconds } ?: 0.0
            val cutout = carProfiles.maxOfOrNull { car -> (car.mass * car.motorAdhesionMassRatio * gravityMetersPerSecondSquared * trainProfile.adhesionCoefficient) / car.equipment.tractionReleaseRateNewtonsPerSecond
            } ?: 0.0
            val serviceScale = trainProfile.normalizedBrakeScale(TrainDynamics.strongestServiceBrakeHandle(trainProfile))
            return planningMarginSeconds +
                (powerSteps + brakeSteps) * controlStepSeconds +
                commandDelay + controlStepSeconds + cutout +
                trainProfile.brakeApplyTime.coerceAtLeast(0.0) * serviceScale +
                jerkResponseSeconds
        }
    }
}
