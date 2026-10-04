package work.art1st.javarailways.simplesignals

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SimpleSignalsConfigTest {
    @Test fun `missing startup value defaults to twenty kmh and custom value converts once`() {
        assertEquals(20.0 / 3.6, SimpleSignalsConfig.from(null).disabledBlockSpeedMetersPerSecond)
        assertEquals(10.0, SimpleSignalsConfig.from(36).disabledBlockSpeedMetersPerSecond)
    }

    @Test fun `invalid startup values are rejected instead of silently defaulting`() {
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, "20", true, Double.MIN_VALUE).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { SimpleSignalsConfig.from(value) }
        }
    }
}
