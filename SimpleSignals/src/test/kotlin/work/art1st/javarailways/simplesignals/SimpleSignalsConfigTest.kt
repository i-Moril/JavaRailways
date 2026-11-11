package work.art1st.javarailways.simplesignals

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SimpleSignalsConfigTest {
    @Test fun `missing region range defaults to one kilometer and custom meters are preserved`() {
        assertEquals(1000.0, SimpleSignalsConfig.from(null).protectionRegionMaxDistanceMeters)
        assertEquals(500.0, SimpleSignalsConfig.from(500).protectionRegionMaxDistanceMeters)
    }

    @Test fun `invalid startup values are rejected instead of silently defaulting`() {
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, "1000", true).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { SimpleSignalsConfig.from(value) }
        }
    }
}
