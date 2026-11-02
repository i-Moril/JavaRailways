package work.art1st.javarailways.simpleato.timetable.simulation

import java.util.concurrent.CancellationException
import work.art1st.javarailways.basic.control.TrainDynamics
import work.art1st.javarailways.basic.control.VehicleControlCapabilityModel
import work.art1st.javarailways.basic.api.ato.*
import work.art1st.javarailways.simpleato.control.*
import work.art1st.javarailways.simpleato.api.atp.AtpTarget
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

/** Physical preview uses the same reference strategy and protection executor as real driving. */
object DetailedTrainTravelSimulator {
    const val STEP_SECONDS = 0.05
    private const val MAX_LEG_SECONDS = 3600.0

    fun simulate(legs: List<DetailedTravelLeg>, profile: DetailedTrainSimulationProfile,
        computationDeadlineNanos: Long = System.nanoTime() + 30_000_000_000L): DetailedTravelSimulationResult {
        require(legs.isNotEmpty() && legs.last().stopAtEnd)
        require(legs.all { it.lengthMeters.isFinite() && it.lengthMeters > 0.0 && it.averageGrade.isFinite() })
        require(profile.cars.isNotEmpty())
        val train = profile.effectiveTrainProfile()
        val capabilities = VehicleControlCapabilityModel(train, profile.cars, profile.gravityMetersPerSecondSquared)
        val executor = VehicleControlExecutor()
        var state = TrainDynamics.initialState(profile.cars)
        var command = VehicleControlCommand.COAST
        val output = mutableListOf<DetailedSimulatedLeg>()
        var first = 0
        while (first < legs.size) {
            var last = first
            while (!legs[last].stopAtEnd) last++
            val group = legs.subList(first, last + 1)
            val total = group.sumOf { it.lengthMeters }
            val boundaries = group.runningFold(0.0) { d, leg -> d + leg.lengthMeters }.drop(1)
            val inputs = group.map { leg -> profile.cars.map { TrainDynamics.CarInput(it, leg.averageGrade) } }
            val adverseGrade = group.minOf { it.averageGrade }
            var distance = 0.0
            var elapsed = 0.0
            var index = 0
            var legStart = 0.0
            var peak = 0.0
            var completed = false
            while (elapsed < MAX_LEG_SECONDS * group.size) {
                if (Thread.currentThread().isInterrupted) throw CancellationException("travel simulation cancelled")
                if (System.nanoTime() >= computationDeadlineNanos)
                    throw DetailedTrainSimulationLimitException("travel simulation exceeded its computation deadline")
                val cars = inputs[index]
                val cap = capabilities.snapshot(state.speed, cars)
                val request = MotionReferenceInput(VehicleControlState(state.speed, state.acceleration,
                    state.brakeScale, command, state.devices.toList()), cap,
                    listOf(MotionReferenceConstraint("preview/stop/$last", total - distance, 0.0)), STEP_SECONDS)
                command = executor.choose(request, listOf(AtpTarget("preview/end/$last",
                    total + AtoSpatialPolicy.POSITION_TOLERANCE_METERS - distance, 0.0)), adverseGrade,
                    VehicleExecutionMode.SIMULATION)
                val before = distance
                val beforeTime = elapsed
                val step = TrainDynamics.step(state, train, cars, command,
                    profile.gravityMetersPerSecondSquared, STEP_SECONDS)
                state = step.state
                distance += step.distance
                elapsed += STEP_SECONDS
                peak = max(peak, state.speed)
                while (index < group.lastIndex && distance >= boundaries[index]) {
                    val fraction = if (distance > before) ((boundaries[index] - before) / (distance - before)).coerceIn(0.0, 1.0) else 1.0
                    val at = beforeTime + fraction * STEP_SECONDS
                    val leg = group[index]
                    output += DetailedSimulatedLeg(leg.fromId, leg.toId, leg.lengthMeters, at - legStart, peak, 0.0)
                    legStart = at
                    peak = state.speed
                    index++
                }
                if (state.speed == 0.0 && distance > 0.0 &&
                    kotlin.math.abs(total - distance) <= AtoSpatialPolicy.POSITION_TOLERANCE_METERS) {
                    val leg = group.last()
                    output += DetailedSimulatedLeg(leg.fromId, leg.toId, leg.lengthMeters,
                        elapsed - legStart, peak, distance - total)
                    completed = true
                    break
                }
            }
            if (!completed) throw DetailedTrainSimulationLimitException("train did not reach ${group.last().toId}")
            first = last + 1
        }
        return DetailedTravelSimulationResult(output)
    }
}
