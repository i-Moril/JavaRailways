package work.art1st.mineRails.core.physics.train

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import work.art1st.mineRails.core.physics.rail.RailLocationWithFacing
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurveGenerator

class CoreTrainLinkTest {
    @Test
    fun `linkWith removes linked train endpoints from rails`() {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(300.0))
        val lead = createTrainWithConfig("lead", rail, world, listOf(10.0), isLoop = false)
        val follower = createTrainWithConfig("follower", rail, world, listOf(10.0), isLoop = false)
        lead.setLocation(RailLocationWithFacing(rail.viewAB, 50.0))
        follower.setLocation(RailLocationWithFacing(rail.viewAB, 35.0))

        assertEquals(2, world.allTrains.size)
        assertEquals(4, rail.trains.size)

        lead.linkWith(follower)
        world.tickManager.postTick()

        assertEquals(listOf("lead"), world.allTrains.map { it.id })
        assertEquals(2, rail.trains.size)
        rail.trains.forEach { end ->
            assertSame(lead, end.trainOrNull)
        }
    }
}
