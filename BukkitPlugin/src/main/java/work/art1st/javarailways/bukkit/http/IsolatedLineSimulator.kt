package work.art1st.javarailways.bukkit.http

import work.art1st.javarailways.bukkit.config.model.TrainControlConfig
import work.art1st.javarailways.bukkit.control.TrainDynamics
import java.util.concurrent.CancellationException
import kotlin.math.max

class SimulationComputationLimitException(message: String) : IllegalStateException(message)

data class SimulationLeg(
    val fromStationId: String,
    val toStationId: String,
    val lengthMeters: Double,
    /** Length-weighted route tangent Y captured before entering the async executor. */
    val averageGrade: Double = 0.0
)
data class SimulatedLeg(
    val fromStationId: String,
    val toStationId: String,
    val lengthMeters: Double,
    val travelSeconds: Double,
    val peakSpeedMetersPerSecond: Double,
    val stoppingErrorMeters: Double
)
data class IsolatedLineSimulationResult(val legs: List<SimulatedLeg>) {
    val totalTravelSeconds: Double get() = legs.sumOf { it.travelSeconds }
}

/** Pure off-thread longitudinal simulation using the same TrainDynamics function as real trains. */
object IsolatedLineSimulator {
    private const val STEP_SECONDS = 0.05
    private const val MAX_LEG_SECONDS = 3_600.0

    fun simulate(legs: List<SimulationLeg>, profile: SimulationControlProfile): IsolatedLineSimulationResult {
        require(legs.isNotEmpty()) { "line simulation requires at least one leg" }
        require(legs.all { it.lengthMeters.isFinite() && it.lengthMeters > 0.0 })
        val config = profile.trainControl
        val carControls = profile.carControls
        val estimatedBrakeDeceleration = max(
            0.2,
            carControls.sumOf { it.brake.maxForce } / carControls.sumOf { it.effectiveMass } * 0.72
        )
        val powerHandle = config.tractionNotchScales.maxByOrNull { it.value }?.key
            ?: throw IllegalArgumentException("selected train profile has no power notch")
        val brakeHandle = config.brakeNotches.asSequence().filterNot { it.equals("EB", true) }
            .maxByOrNull { config.normalizedBrakeScale(it) }
            ?: config.brakeNotches.maxByOrNull { config.normalizedBrakeScale(it) }
            ?: throw IllegalArgumentException("selected train profile has no brake notch")
        val deadlineNanos = System.nanoTime() + 30L * 1_000_000_000L
        return IsolatedLineSimulationResult(legs.map { leg ->
            val cars = carControls.map { TrainDynamics.CarInput(it, leg.averageGrade) }
            simulateLeg(leg, config, cars, estimatedBrakeDeceleration, powerHandle, brakeHandle, deadlineNanos)
        })
    }

    private fun simulateLeg(
        leg: SimulationLeg,
        config: TrainControlConfig,
        cars: List<TrainDynamics.CarInput>,
        brakeDeceleration: Double,
        powerHandle: String,
        brakeHandle: String,
        deadlineNanos: Long
    ): SimulatedLeg {
        var state = TrainDynamics.State()
        var distance = 0.0
        var elapsed = 0.0
        var peakSpeed = 0.0
        while (elapsed < MAX_LEG_SECONDS) {
            if (Thread.currentThread().isInterrupted) throw CancellationException("line simulation cancelled")
            if (System.nanoTime() >= deadlineNanos) {
                throw SimulationComputationLimitException("line simulation exceeded its computation deadline")
            }
            val remaining = leg.lengthMeters - distance
            val stoppingDistance = state.speed * state.speed / (2.0 * brakeDeceleration) + state.speed * 0.8 + 0.5
            val handle = if (remaining <= stoppingDistance) brakeHandle else powerHandle
            val result = TrainDynamics.step(state, config, cars, handle, STEP_SECONDS)
            state = result.state
            distance += result.distance
            elapsed += STEP_SECONDS
            peakSpeed = max(peakSpeed, state.speed)
            if (remaining <= 2.0 && state.speed <= 0.05) {
                return SimulatedLeg(
                    leg.fromStationId,
                    leg.toStationId,
                    leg.lengthMeters,
                    elapsed,
                    peakSpeed,
                    distance - leg.lengthMeters
                )
            }
        }
        error("simulation did not stop within ${MAX_LEG_SECONDS.toInt()} seconds: ${leg.fromStationId} -> ${leg.toStationId}")
    }
}
