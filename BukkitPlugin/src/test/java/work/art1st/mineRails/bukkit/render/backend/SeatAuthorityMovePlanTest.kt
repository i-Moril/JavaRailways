package work.art1st.mineRails.bukkit.render.backend

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SeatAuthorityMovePlanTest {
    @Test
    fun `authority uses the exact target delta instead of velocity simulation`() {
        val plan = SeatAuthorityMovePlan.between(
            SeatAuthorityPoint(-10.0, 64.0, 20.0),
            SeatAuthorityPoint(-7.5, 63.25, 17.0)
        )

        assertEquals(2.5, plan.dx)
        assertEquals(-0.75, plan.dy)
        assertEquals(-3.0, plan.dz)
    }

    @Test
    fun `residual error is measured after native movement`() {
        val target = SeatAuthorityPoint(12.0, 30.0, -4.0)

        assertEquals(0.0, SeatAuthorityMovePlan.residual(target, target))
        assertEquals(13.0, SeatAuthorityMovePlan.residual(
            SeatAuthorityPoint(0.0, 30.0, 1.0),
            target
        ))
    }
}
