package work.art1st.javarailways.bukkit.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.config.model.CarControlConfig
import work.art1st.javarailways.bukkit.config.model.MotorControlConfig
import work.art1st.javarailways.bukkit.config.model.TrainControlConfig

class IsolatedLineSimulatorTest {
    private val profile = SimulationControlProfile(
        SimulationProfileType.TRAIN_TEMPLATE,
        "test",
        TrainControlConfig(),
        List(6) { index -> CarControlConfig(motor = MotorControlConfig(
            enabled = index < 4,
            maxForce = if (index < 4) 40_000.0 else 0.0,
            maxPower = if (index < 4) 500_000.0 else 0.0
        )) }
    )

    @Test
    fun `simulates each line leg without a live train`() {
        val result = IsolatedLineSimulator.simulate(
            listOf(
                SimulationLeg("A", "B", 500.0),
                SimulationLeg("B", "C", 800.0)
            ),
            profile,
            gravityMetersPerSecondSquared = 9.80665
        )

        assertEquals(2, result.legs.size)
        assertTrue(result.totalTravelSeconds > 0.0)
        assertTrue(result.legs.all { it.peakSpeedMetersPerSecond > 0.0 })
        assertTrue(result.legs.all { kotlin.math.abs(it.stoppingErrorMeters) < 8.0 })
    }

    @Test
    fun `keeps speed through pass stations while returning adjacent crossing intervals`() {
        val legs = listOf(
            SimulationLeg("A", "B", 500.0, stopAtEnd = false),
            SimulationLeg("B", "C", 500.0, stopAtEnd = false),
            SimulationLeg("C", "D", 500.0, stopAtEnd = true)
        )
        val passing = IsolatedLineSimulator.simulate(legs, profile, 9.80665)
        val allStops = IsolatedLineSimulator.simulate(
            legs.map { it.copy(stopAtEnd = true) }, profile, 9.80665
        )

        assertEquals(listOf("B", "C", "D"), passing.legs.map { it.toStationId })
        assertTrue(passing.legs.all { it.travelSeconds > 0.0 })
        assertTrue(passing.totalTravelSeconds < allStops.totalTravelSeconds)
    }

    @Test
    fun `applies the resolved timetable line maximum speed to detailed simulation`() {
        val uncapped = IsolatedLineSimulator.simulate(
            listOf(SimulationLeg("A", "B", 1_000.0)),
            profile,
            gravityMetersPerSecondSquared = 9.80665
        )
        val capped = IsolatedLineSimulator.simulate(
            listOf(SimulationLeg("A", "B", 1_000.0)),
            profile,
            gravityMetersPerSecondSquared = 9.80665,
            lineMaximumSpeedMetersPerSecond = resolveLineSimulationMaximumSpeed(
                Json.parseToJsonElement("""{"maximumSpeedMetersPerSecond":5.0}""").jsonObject
            ) { _, _ -> error("authoring Reset must not read a stored timetable") }
        )

        assertTrue(capped.legs.single().peakSpeedMetersPerSecond <= 5.0)
        assertTrue(capped.totalTravelSeconds > uncapped.totalTravelSeconds)
    }

    @Test
    fun `honors worker interruption`() {
        Thread.currentThread().interrupt()
        try {
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                IsolatedLineSimulator.simulate(
                    listOf(SimulationLeg("A", "B", 500.0)),
                    profile,
                    gravityMetersPerSecondSquared = 9.80665
                )
            }
        } finally {
            Thread.interrupted()
        }
    }
}
