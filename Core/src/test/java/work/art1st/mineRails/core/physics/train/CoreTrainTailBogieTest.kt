package work.art1st.mineRails.core.physics.train

import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurveGenerator

class CoreTrainTailBogieTest {
    @Test
    fun `tail bogie uses last car in forward direction`() {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0))
        val train = createTrainWithConfig("t-tail", rail, world, listOf(10.0, 10.0), isLoop = false)

        assertSame(train.cars.last().bogieB, train.tailBogie)
    }

    @Test
    fun `tail bogie switches to first car when reversed`() {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0))
        val train = createTrainWithConfig("t-tail-reversed", rail, world, listOf(10.0, 10.0), isLoop = false)

        train.reverse()

        assertSame(train.cars.first().bogieA, train.tailBogie)
    }
}
