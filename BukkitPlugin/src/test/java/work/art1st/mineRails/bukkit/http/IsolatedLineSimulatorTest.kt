package work.art1st.mineRails.bukkit.http

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.mineRails.bukkit.config.model.CarControlConfig
import work.art1st.mineRails.bukkit.config.model.MotorControlConfig
import work.art1st.mineRails.bukkit.config.model.TrainControlConfig

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
            profile
        )

        assertEquals(2, result.legs.size)
        assertTrue(result.totalTravelSeconds > 0.0)
        assertTrue(result.legs.all { it.peakSpeedMetersPerSecond > 0.0 })
        assertTrue(result.legs.all { kotlin.math.abs(it.stoppingErrorMeters) < 8.0 })
    }

    @Test
    fun `honors worker interruption`() {
        Thread.currentThread().interrupt()
        try {
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                IsolatedLineSimulator.simulate(
                    listOf(SimulationLeg("A", "B", 500.0)),
                    profile
                )
            }
        } finally {
            Thread.interrupted()
        }
    }
}
