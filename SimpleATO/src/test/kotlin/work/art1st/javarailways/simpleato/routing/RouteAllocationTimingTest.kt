package work.art1st.javarailways.simpleato.routing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RouteAllocationTimingTest {
    @Test
    fun `allocation trigger includes braking setup reaction and margin distances`() {
        val timing = RouteAllocationTiming(
            switchSetupSeconds = 0.2,
            reactionTimeSeconds = 0.1,
            safetyMarginMeters = 10.0
        )

        assertEquals(216.0, timing.triggerDistanceMeters(20.0, 1.0), 1.0e-9)
    }
}
