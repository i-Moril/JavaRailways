package work.art1st.mineRails.bukkit.extension.rail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RailDecorationLayoutTest {
    @Test
    fun `placement includes both endpoints and never exceeds repeat length`() {
        val arcs = RailDecorationLayout.endpointInclusiveArcs(
            totalArcLength = 10.1,
            repeatLength = 0.95
        )

        assertEquals(0.0, arcs.first(), 1.0e-12)
        assertEquals(10.1, arcs.last(), 1.0e-12)
        assertTrue(arcs.zipWithNext().all { (a, b) -> b - a <= 0.95 + 1.0e-12 })
    }

    @Test
    fun `short curve still places models at both endpoints`() {
        assertEquals(
            listOf(0.0, 0.4),
            RailDecorationLayout.endpointInclusiveArcs(0.4, 0.95)
        )
    }

    @Test
    fun `segment partition assigns boundary point exactly once`() {
        val arcs = RailDecorationLayout.endpointInclusiveArcs(16.0, 1.0)
        val first = RailDecorationLayout.arcsInSegment(arcs, 0.0, 8.0, includeEnd = false)
        val second = RailDecorationLayout.arcsInSegment(arcs, 8.0, 16.0, includeEnd = true)

        assertEquals(1, (first + second).count { it == 8.0 })
        assertEquals(arcs, first + second)
    }
}
