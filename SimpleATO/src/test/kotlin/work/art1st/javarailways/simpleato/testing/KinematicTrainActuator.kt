package work.art1st.javarailways.simpleato.testing

import work.art1st.javarailways.basic.api.ato.ActuationStep
import work.art1st.javarailways.basic.api.ato.ControlDemand
import work.art1st.javarailways.basic.api.ato.TrainActuator
import work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile

/** Constant-acceleration actuator for small, deterministic planner and constraint tests. */
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
