package work.art1st.javarailways.simpleato.timetable.simulation

import java.util.concurrent.CancellationException
import work.art1st.javarailways.basic.control.TrainDynamics
import work.art1st.javarailways.basic.control.HandleTrainActuator
import kotlin.math.max
import kotlin.math.min

class DetailedTrainSimulationLimitException(message: String) : IllegalStateException(message)

data class DetailedTravelLeg(
    val fromId: String,
    val toId: String,
    val lengthMeters: Double,
    val averageGrade: Double = 0.0,
    val stopAtEnd: Boolean = true
)

data class DetailedSimulatedLeg(
    val fromId: String,
    val toId: String,
    val lengthMeters: Double,
    val travelSeconds: Double,
    val peakSpeedMetersPerSecond: Double,
    val stoppingErrorMeters: Double
)

data class DetailedTrainSimulationProfile(
    val train: TrainDynamics.TrainProfile,
    val cars: List<TrainDynamics.CarProfile>,
    val gravityMetersPerSecondSquared: Double,
    val maximumSpeedMetersPerSecond: Double? = null
) {
    init {
        require(gravityMetersPerSecondSquared.isFinite() && gravityMetersPerSecondSquared > 0.0)
        require(maximumSpeedMetersPerSecond == null ||
            maximumSpeedMetersPerSecond.isFinite() && maximumSpeedMetersPerSecond > 0.0)
    }

    fun effectiveTrainProfile(): TrainDynamics.TrainProfile {
        val limit = maximumSpeedMetersPerSecond ?: return train
        return SpeedCappedTrainProfile(train, min(train.maxSpeed, limit))
    }
}

private class SpeedCappedTrainProfile(
    private val delegate: TrainDynamics.TrainProfile,
    override val maxSpeed: Double
) : TrainDynamics.TrainProfile by delegate

data class DetailedTravelSimulationResult(val legs: List<DetailedSimulatedLeg>) {
    val totalTravelSeconds: Double get() = legs.sumOf(DetailedSimulatedLeg::travelSeconds)
}

/** Fixed-0.05-second physical integration reusable by line preview and full timetable simulation. */
object DetailedTrainTravelSimulator {
    const val STEP_SECONDS = 0.05
    private const val MAX_LEG_SECONDS = 3_600.0

    fun simulate(
        legs: List<DetailedTravelLeg>,
        profile: DetailedTrainSimulationProfile,
        computationDeadlineNanos: Long = System.nanoTime() + 30_000_000_000L
    ): DetailedTravelSimulationResult {
        require(legs.isNotEmpty()) { "travel simulation requires at least one leg" }
        require(legs.all { it.lengthMeters.isFinite() && it.lengthMeters > 0.0 })
        require(profile.cars.isNotEmpty()) { "travel simulation requires at least one car" }
        val effectiveProfile = profile.copy(train = profile.effectiveTrainProfile())
        // Preserve the preview's existing 72% service-brake switching heuristic. Actual equipment
        // follows the shared dynamics; this approximate preview is not the ATO authority proof.
        val estimatedBrakeDeceleration = TrainDynamics.flatServiceDeceleration(
            effectiveProfile.train, effectiveProfile.cars, effectiveProfile.gravityMetersPerSecondSquared) * 0.72
        require(estimatedBrakeDeceleration.isFinite() && estimatedBrakeDeceleration > 0.0) {
            "selected train profile has no guaranteed pneumatic service braking"
        }
        require(legs.last().stopAtEnd) { "the final travel leg must end at a stop" }
        val powerHandle = findPowerHandle(effectiveProfile.train)
        val brakeHandle = findBrakeHandle(effectiveProfile.train)
        val stoppingGroups = buildList {
            var start = 0
            legs.forEachIndexed { index, leg ->
                if (leg.stopAtEnd) {
                    add(legs.subList(start, index + 1))
                    start = index + 1
                }
            }
        }
        var state = TrainDynamics.initialState(effectiveProfile.cars)
        val simulated = mutableListOf<DetailedSimulatedLeg>()
        for (group in stoppingGroups) {
            val result = simulateStoppingGroup(
                group, effectiveProfile, estimatedBrakeDeceleration, powerHandle, brakeHandle,
                computationDeadlineNanos, state
            )
            simulated += result.legs
            // A new leg does not create a new vehicle or discharge its braking equipment.
            state = result.state
        }
        return DetailedTravelSimulationResult(simulated)
    }

    private data class StoppingGroupResult(val legs: List<DetailedSimulatedLeg>, val state: TrainDynamics.State)

    private fun findPowerHandle(profile: TrainDynamics.TrainProfile): String =
        profile.powerHandles.maxByOrNull(profile::normalizedPowerScale)
            ?.takeIf { profile.normalizedPowerScale(it) > 0.0 }
            ?: throw IllegalArgumentException("selected train profile has no power notch")

    private fun findBrakeHandle(profile: TrainDynamics.TrainProfile): String =
        profile.brakeHandles.asSequence().filterNot { it.equals("EB", true) }
            .maxByOrNull(profile::normalizedBrakeScale)
            ?: profile.brakeHandles.maxByOrNull(profile::normalizedBrakeScale)
            ?: throw IllegalArgumentException("selected train profile has no brake notch")

    private fun simulateStoppingGroup(
        legs: List<DetailedTravelLeg>,
        profile: DetailedTrainSimulationProfile,
        brakeDeceleration: Double,
        powerHandle: String,
        brakeHandle: String,
        deadlineNanos: Long,
        initialState: TrainDynamics.State
    ): StoppingGroupResult {
        val totalLength = legs.sumOf(DetailedTravelLeg::lengthMeters)
        val boundaries = legs.runningFold(0.0) { distance, leg -> distance + leg.lengthMeters }.drop(1)
        val simulated = mutableListOf<DetailedSimulatedLeg>()
        var state = initialState
        var distance = 0.0
        var elapsed = 0.0
        var legIndex = 0
        var legStartedAt = 0.0
        var legPeakSpeed = 0.0
        val reactionSeconds = HandleTrainActuator.serviceBrakeReactionTimeSeconds(profile.train,
            profile.cars, profile.gravityMetersPerSecondSquared)
        val carInputsByLeg = legs.map { leg -> profile.cars.map { TrainDynamics.CarInput(it, leg.averageGrade) } }
        while (elapsed < MAX_LEG_SECONDS * legs.size) {
            if (Thread.currentThread().isInterrupted) throw CancellationException("travel simulation cancelled")
            if (System.nanoTime() >= deadlineNanos) {
                throw DetailedTrainSimulationLimitException("travel simulation exceeded its computation deadline")
            }
            val remaining = totalLength - distance
            val stoppingDistance = state.speed * state.speed / (2.0 * brakeDeceleration) + state.speed * reactionSeconds + 0.5
            val handle = if (remaining <= stoppingDistance) brakeHandle else powerHandle
            val cars = carInputsByLeg[legIndex.coerceAtMost(legs.lastIndex)]
            val beforeDistance = distance
            val beforeElapsed = elapsed
            val result = TrainDynamics.step(
                state,
                profile.train,
                cars,
                handle,
                profile.gravityMetersPerSecondSquared,
                STEP_SECONDS
            )
            state = result.state
            distance += result.distance
            elapsed += STEP_SECONDS
            legPeakSpeed = max(legPeakSpeed, state.speed)

            while (legIndex < legs.lastIndex && distance >= boundaries[legIndex]) {
                val crossingFraction = if (distance > beforeDistance) {
                    ((boundaries[legIndex] - beforeDistance) / (distance - beforeDistance)).coerceIn(0.0, 1.0)
                } else 1.0
                val crossingElapsed = beforeElapsed + STEP_SECONDS * crossingFraction
                val leg = legs[legIndex]
                simulated += DetailedSimulatedLeg(
                    leg.fromId,
                    leg.toId,
                    leg.lengthMeters,
                    crossingElapsed - legStartedAt,
                    legPeakSpeed,
                    0.0
                )
                legStartedAt = crossingElapsed
                legIndex += 1
                legPeakSpeed = state.speed
            }

            if (remaining <= 2.0 && state.speed <= 0.05) {
                val leg = legs.last()
                simulated += DetailedSimulatedLeg(
                    leg.fromId,
                    leg.toId,
                    leg.lengthMeters,
                    elapsed - legStartedAt,
                    legPeakSpeed,
                    distance - totalLength
                )
                return StoppingGroupResult(simulated, state)
            }
        }
        error(
            "simulation did not stop within ${(MAX_LEG_SECONDS * legs.size).toInt()} seconds: " +
                "${legs.first().fromId} -> ${legs.last().toId}"
        )
    }
}
