package work.art1st.javarailways.basic.timetable.simulation

import java.util.concurrent.CancellationException
import work.art1st.javarailways.basic.control.TrainDynamics
import kotlin.math.max

class DetailedTrainSimulationLimitException(message: String) : IllegalStateException(message)

data class DetailedTravelLeg(
    val fromId: String,
    val toId: String,
    val lengthMeters: Double,
    val averageGrade: Double = 0.0
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
    val cars: List<TrainDynamics.CarProfile>
)

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
        val estimatedBrakeDeceleration = max(
            0.2,
            profile.cars.sumOf(TrainDynamics.CarProfile::brakeMaxForce) /
                profile.cars.sumOf(TrainDynamics.CarProfile::effectiveMass) * 0.72
        )
        val powerHandle = findPowerHandle(profile.train)
        val brakeHandle = findBrakeHandle(profile.train)
        return DetailedTravelSimulationResult(legs.map { leg ->
            simulateLeg(
                leg,
                profile,
                estimatedBrakeDeceleration,
                powerHandle,
                brakeHandle,
                computationDeadlineNanos
            )
        })
    }

    private fun findPowerHandle(profile: TrainDynamics.TrainProfile): String =
        profile.powerHandles.maxByOrNull(profile::normalizedPowerScale)
            ?.takeIf { profile.normalizedPowerScale(it) > 0.0 }
            ?: throw IllegalArgumentException("selected train profile has no power notch")

    private fun findBrakeHandle(profile: TrainDynamics.TrainProfile): String =
        profile.brakeHandles.asSequence().filterNot { it.equals("EB", true) }
            .maxByOrNull(profile::normalizedBrakeScale)
            ?: profile.brakeHandles.maxByOrNull(profile::normalizedBrakeScale)
            ?: throw IllegalArgumentException("selected train profile has no brake notch")

    private fun simulateLeg(
        leg: DetailedTravelLeg,
        profile: DetailedTrainSimulationProfile,
        brakeDeceleration: Double,
        powerHandle: String,
        brakeHandle: String,
        deadlineNanos: Long
    ): DetailedSimulatedLeg {
        val cars = profile.cars.map { TrainDynamics.CarInput(it, leg.averageGrade) }
        var state = TrainDynamics.State()
        var distance = 0.0
        var elapsed = 0.0
        var peakSpeed = 0.0
        while (elapsed < MAX_LEG_SECONDS) {
            if (Thread.currentThread().isInterrupted) throw CancellationException("travel simulation cancelled")
            if (System.nanoTime() >= deadlineNanos) {
                throw DetailedTrainSimulationLimitException("travel simulation exceeded its computation deadline")
            }
            val remaining = leg.lengthMeters - distance
            val stoppingDistance = state.speed * state.speed / (2.0 * brakeDeceleration) + state.speed * 0.8 + 0.5
            val handle = if (remaining <= stoppingDistance) brakeHandle else powerHandle
            val result = TrainDynamics.step(state, profile.train, cars, handle, STEP_SECONDS)
            state = result.state
            distance += result.distance
            elapsed += STEP_SECONDS
            peakSpeed = max(peakSpeed, state.speed)
            if (remaining <= 2.0 && state.speed <= 0.05) {
                return DetailedSimulatedLeg(
                    leg.fromId,
                    leg.toId,
                    leg.lengthMeters,
                    elapsed,
                    peakSpeed,
                    distance - leg.lengthMeters
                )
            }
        }
        error("simulation did not stop within ${MAX_LEG_SECONDS.toInt()} seconds: ${leg.fromId} -> ${leg.toId}")
    }
}
