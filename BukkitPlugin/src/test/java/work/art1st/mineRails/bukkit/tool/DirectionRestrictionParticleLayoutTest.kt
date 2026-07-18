package work.art1st.mineRails.bukkit.tool

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DirectionRestrictionParticleLayoutTest {
    @Test
    fun `long rails never exceed per rail particle budget`() {
        val arcs = DirectionRestrictionParticleLayout.arcLengths(500.0, phase = 7.0)

        assertTrue(arcs.size <= 6)
        assertTrue(arcs.zipWithNext().all { (a, b) -> b > a })
        assertTrue(arcs.all { it in 0.0..500.0 })
    }

    @Test
    fun `short rail always has a moving flow point`() {
        val first = DirectionRestrictionParticleLayout.arcLengths(5.0, phase = 1.0)
        val second = DirectionRestrictionParticleLayout.arcLengths(5.0, phase = 2.0)

        assertEquals(1, first.size)
        assertEquals(1, second.size)
        assertTrue(second.single() > first.single())
    }

    @Test
    fun `waypoint approach particles stay on the arrival side of the target`() {
        val arcs = WayPointApproachParticleLayout.arcLengths(
            destinationArcInView = 50.0,
            phase = 1.5
        )

        assertTrue(arcs.isNotEmpty())
        assertTrue(arcs.all { it in 26.0..50.0 })
        assertTrue(arcs.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun `waypoint approach layout clamps to the start of a short view`() {
        val arcs = WayPointApproachParticleLayout.arcLengths(
            destinationArcInView = 6.0,
            phase = 1.0
        )

        assertTrue(arcs.all { it in 0.0..6.0 })
    }

    @Test
    fun `invalid geometry produces no particles`() {
        assertTrue(DirectionRestrictionParticleLayout.arcLengths(0.0, 1.0).isEmpty())
        assertTrue(DirectionRestrictionParticleLayout.arcLengths(10.0, 1.0, maxPoints = 0).isEmpty())
        assertTrue(WayPointApproachParticleLayout.arcLengths(-1.0, 1.0).isEmpty())
    }
}
