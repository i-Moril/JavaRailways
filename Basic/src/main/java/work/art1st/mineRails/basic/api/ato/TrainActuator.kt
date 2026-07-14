package work.art1st.mineRails.basic.api.ato

/** Desired speed selected by an ATO planner. */
data class ControlDemand(val targetSpeedMetersPerSecond: Double) {
    init {
        require(targetSpeedMetersPerSecond >= 0.0 && targetSpeedMetersPerSecond.isFinite()) {
            "targetSpeedMetersPerSecond must be finite and non-negative"
        }
    }
}

/** Value-only result applied by the owning TrainController. */
data class ActuationStep(
    val speedMetersPerSecond: Double,
    val distanceMeters: Double
) {
    init {
        require(speedMetersPerSecond >= 0.0 && speedMetersPerSecond.isFinite())
        require(distanceMeters >= 0.0 && distanceMeters.isFinite())
    }
}

interface TrainActuator {
    val maximumSpeedMetersPerSecond: Double

    fun step(
        currentSpeedMetersPerSecond: Double,
        demand: ControlDemand,
        deltaTimeSeconds: Double
    ): ActuationStep

    fun freeze(): ActuationStep = ActuationStep(0.0, 0.0)
}

/** Constant-acceleration actuator used by SimpleATO. */
class KinematicTrainActuator(
    override val maximumSpeedMetersPerSecond: Double,
    private val profile: AtoSafetyProfile
) : TrainActuator {
    init {
        require(maximumSpeedMetersPerSecond > 0.0 && maximumSpeedMetersPerSecond.isFinite())
    }

    override fun step(
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
}
