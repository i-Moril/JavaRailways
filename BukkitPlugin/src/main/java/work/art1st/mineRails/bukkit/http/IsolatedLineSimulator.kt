package work.art1st.mineRails.bukkit.http

import work.art1st.mineRails.bukkit.config.model.CarBrakeControlConfig
import work.art1st.mineRails.bukkit.config.model.CarControlConfig
import work.art1st.mineRails.bukkit.config.model.MotorControlConfig
import work.art1st.mineRails.bukkit.config.model.TrainControlConfig
import work.art1st.mineRails.bukkit.control.TrainDynamics
import java.util.concurrent.CancellationException
import kotlin.math.max

class SimulationComputationLimitException(message: String) : IllegalStateException(message)

/** Immutable user-created train profile; it never creates or mutates a CoreTrain. */
data class SimulationTrainProfile(
    val maxSpeedMetersPerSecond: Double = 20.0,
    val carCount: Int = 6,
    val poweredCars: Int = 4,
    val carMassKilograms: Double = 32_000.0,
    val tractionForcePerPoweredCarNewtons: Double = 40_000.0,
    val tractionPowerPerPoweredCarWatts: Double = 500_000.0,
    val brakeForcePerCarNewtons: Double = 45_000.0
) {
    init {
        require(maxSpeedMetersPerSecond in 0.1..200.0)
        require(carCount in 1..64 && poweredCars in 1..carCount)
        require(carMassKilograms in 100.0..1_000_000.0)
        require(tractionForcePerPoweredCarNewtons in 1.0..10_000_000.0)
        require(tractionPowerPerPoweredCarWatts in 1.0..100_000_000.0)
        require(brakeForcePerCarNewtons in 1.0..10_000_000.0)
    }
}

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

    fun simulate(legs: List<SimulationLeg>, profile: SimulationTrainProfile): IsolatedLineSimulationResult {
        require(legs.isNotEmpty()) { "line simulation requires at least one leg" }
        require(legs.all { it.lengthMeters.isFinite() && it.lengthMeters > 0.0 })
        val config = TrainControlConfig(maxSpeed = profile.maxSpeedMetersPerSecond)
        val carControls = List(profile.carCount) { index ->
            CarControlConfig(
                mass = profile.carMassKilograms,
                motor = MotorControlConfig(
                    enabled = index < profile.poweredCars,
                    maxForce = if (index < profile.poweredCars) profile.tractionForcePerPoweredCarNewtons else 0.0,
                    maxPower = if (index < profile.poweredCars) profile.tractionPowerPerPoweredCarWatts else 0.0
                ),
                brake = CarBrakeControlConfig(maxForce = profile.brakeForcePerCarNewtons)
            )
        }
        val estimatedBrakeDeceleration = max(
            0.2,
            profile.brakeForcePerCarNewtons * profile.carCount /
                (profile.carMassKilograms * profile.carCount * 1.08) * 0.72
        )
        val deadlineNanos = System.nanoTime() + 30L * 1_000_000_000L
        return IsolatedLineSimulationResult(legs.map { leg ->
            val cars = carControls.map { TrainDynamics.CarInput(it, leg.averageGrade) }
            simulateLeg(leg, config, cars, estimatedBrakeDeceleration, deadlineNanos)
        })
    }

    private fun simulateLeg(
        leg: SimulationLeg,
        config: TrainControlConfig,
        cars: List<TrainDynamics.CarInput>,
        brakeDeceleration: Double,
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
            val handle = if (remaining <= stoppingDistance) "B7" else "P5"
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
