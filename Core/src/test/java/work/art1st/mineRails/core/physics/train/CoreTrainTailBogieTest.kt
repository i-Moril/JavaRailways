package work.art1st.mineRails.core.physics.train

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import work.art1st.mineRails.core.physics.rail.RailLocationWithFacing
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.mineRails.core.util.ThreadGuard
import work.art1st.mineRails.core.util.ThreadGuardPlatformBridge

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

        train.reverseOnMainThread()

        assertSame(train.cars.first().bogieA, train.tailBogie)
    }

    @Test
    fun `reversed train keeps cars spaced while moving`() {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(1000.0))
        val train = createTrainWithConfig("t-reverse-spacing", rail, world, listOf(10.0, 10.0, 10.0), isLoop = false)

        train.setLocation(RailLocationWithFacing(rail.viewAB, 150.0))
        train.commit()
        val directionsBeforeReverse = train.cars.map { org.joml.Vector3d(it.direction) }
        train.reverseOnMainThread()
        train.moveTrainForward(5.0)
        train.commit()

        assertFalse(train.isDerailed)
        val d01 = train.cars[0].position.distance(train.cars[1].position)
        val d12 = train.cars[1].position.distance(train.cars[2].position)
        assertEquals(10.0, d01, 0.5, "car 0/1 spacing")
        assertEquals(10.0, d12, 0.5, "car 1/2 spacing")
        train.cars.forEachIndexed { index, car ->
            assertEquals(1.0, directionsBeforeReverse[index].dot(car.direction), 1e-9, "car $index visual direction should not flip")
        }
    }

    private fun CoreTrain.reverseOnMainThread() {
        val owner = Thread.currentThread()
        ThreadGuard.installPlatformBridge(object : ThreadGuardPlatformBridge {
            override fun isMainThread(): Boolean = Thread.currentThread() === owner
        })
        try {
            reverse()
        } finally {
            ThreadGuard.clearPlatformBridge()
        }
    }
}
