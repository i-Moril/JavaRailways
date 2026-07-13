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
    fun `invalid geometry produces no particles`() {
        assertTrue(DirectionRestrictionParticleLayout.arcLengths(0.0, 1.0).isEmpty())
        assertTrue(DirectionRestrictionParticleLayout.arcLengths(10.0, 1.0, maxPoints = 0).isEmpty())
    }
}
