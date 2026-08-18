package work.art1st.javarailways.bukkit.extension.rail

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

    @Test
    fun `render sampling clamps persisted endpoint within floating point tolerance`() {
        val currentTotal = 28.216159744557498
        val persistedEndpoint = 28.2161597445575

        val arcs = RailDecorationLayout.sanitizeArcsInSegment(
            listOf(0.0, persistedEndpoint),
            arcStart = 0.0,
            arcEnd = currentTotal
        )

        assertEquals(listOf(0.0, currentTotal), arcs)
    }

    @Test
    fun `render sampling drops stale and non finite arc lengths`() {
        val arcs = RailDecorationLayout.sanitizeArcsInSegment(
            listOf(-1.0, 2.0, Double.NaN, 12.0),
            arcStart = 0.0,
            arcEnd = 10.0
        )

        assertEquals(listOf(2.0), arcs)
    }
}
