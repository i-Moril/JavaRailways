package work.art1st.javarailways.bukkit.http

import work.art1st.javarailways.basic.timetable.simulation.DetailedTrainSimulationLimitException
import work.art1st.javarailways.basic.timetable.simulation.DetailedTrainSimulationProfile
import work.art1st.javarailways.basic.timetable.simulation.DetailedTrainTravelSimulator
import work.art1st.javarailways.basic.timetable.simulation.DetailedTravelLeg

class SimulationComputationLimitException(message: String) : IllegalStateException(message)

data class SimulationLeg(
    val fromStationId: String,
    val toStationId: String,
    val lengthMeters: Double,
    /** Length-weighted route tangent Y captured before entering the async executor. */
    val averageGrade: Double = 0.0,
    /** False keeps train state through this station and reports a crossing interval. */
    val stopAtEnd: Boolean = true
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

/** Bukkit DTO adapter around Basic's pure fixed-step detailed travel simulator. */
object IsolatedLineSimulator {
    fun simulate(
        legs: List<SimulationLeg>,
        profile: SimulationControlProfile,
        lineMaximumSpeedMetersPerSecond: Double? = null
    ): IsolatedLineSimulationResult {
        val result = try {
            DetailedTrainTravelSimulator.simulate(
                legs.map { DetailedTravelLeg(
                    it.fromStationId, it.toStationId, it.lengthMeters, it.averageGrade, it.stopAtEnd
                ) },
                DetailedTrainSimulationProfile(
                    profile.trainControl,
                    profile.carControls,
                    lineMaximumSpeedMetersPerSecond
                )
            )
        } catch (limit: DetailedTrainSimulationLimitException) {
            throw SimulationComputationLimitException(limit.message ?: "line simulation exceeded its computation deadline")
        }
        return IsolatedLineSimulationResult(result.legs.map { leg -> SimulatedLeg(
            leg.fromId,
            leg.toId,
            leg.lengthMeters,
            leg.travelSeconds,
            leg.peakSpeedMetersPerSecond,
            leg.stoppingErrorMeters
        ) })
    }
}
