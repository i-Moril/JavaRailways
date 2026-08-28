package work.art1st.javarailways.bukkit.tool

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RailWandSuperelevationPolicyTest {
    @Test
    fun `disabled superelevation produces no curve superelevation config`() {
        assertNull(
            railWandSuperelevationConfig(
                enabled = false,
                designSpeedKmH = 120.0,
                balanceSpeedOffsetKmH = 10.0,
                maximumCantGradient = 1.0 / 300.0,
                gaugeMeters = 1.435,
                gravityMetersPerSecondSquared = 9.80665
            )
        )
    }

    @Test
    fun `enabled superelevation always uses automatic balance cant with a fixed safety cap`() {
        val config = railWandSuperelevationConfig(
            enabled = true,
            designSpeedKmH = 120.0,
            balanceSpeedOffsetKmH = 10.0,
            maximumCantGradient = 1.0 / 300.0,
            gaugeMeters = 1.435,
            gravityMetersPerSecondSquared = 9.80665
        )!!

        assertTrue(config.autoCalculate)
        assertEquals(110.0, config.designSpeedKmH, 1.0e-9)
        assertEquals(0.15, config.maxSuperelevation, 1.0e-9)
    }

}
