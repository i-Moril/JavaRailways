package work.art1st.javarailways.tunnelbridgebuilder.rail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RailSweepSamplingTest {
    @Test
    fun `persisted floating point length is sampled at its exact endpoint`() {
        val totalArcLength = 234.97013186581603

        val arcs = railSweepSampleArcLengths(totalArcLength, 0.5)

        assertEquals(0.0, arcs.first())
        assertEquals(totalArcLength, arcs.last())
        assertTrue(arcs.all { it in 0.0..totalArcLength })
    }
}
