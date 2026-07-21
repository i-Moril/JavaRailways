package work.art1st.mineRails.bukkit.http

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class IsolatedLineSimulatorTest {
    @Test
    fun `simulates each line leg without a live train`() {
        val result = IsolatedLineSimulator.simulate(
            listOf(
                SimulationLeg("A", "B", 500.0),
                SimulationLeg("B", "C", 800.0)
            ),
            SimulationTrainProfile()
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
                    SimulationTrainProfile()
                )
            }
        } finally {
            Thread.interrupted()
        }
    }
}
