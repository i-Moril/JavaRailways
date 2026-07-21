package work.art1st.mineRails.bukkit.control

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import work.art1st.mineRails.bukkit.config.model.DriverControlEnd
import work.art1st.mineRails.core.api.control.TrainController
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.physics.train.Train
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.mineRails.core.physics.train.createStraightRailFromSegments
import work.art1st.mineRails.core.physics.train.createTestWorld
import work.art1st.mineRails.core.physics.train.createTrainWithConfig
import work.art1st.mineRails.core.util.ThreadGuard
import work.art1st.mineRails.core.util.ThreadGuardPlatformBridge

class TrainControlManagerDirectionTest {
    @BeforeEach
    fun installMainThreadBridge() {
        ThreadGuard.installPlatformBridge(object : ThreadGuardPlatformBridge {
            override fun isMainThread(): Boolean = true
        })
    }

    @AfterEach
    fun clearMainThreadBridge() {
        ThreadGuard.clearPlatformBridge()
    }

    @Test
    fun `opposite cab reverses train immediately instead of queuing on outgoing controller`() {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(80.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val outgoing = ReverseTrackingController(train, world)
        train.setController(outgoing)

        alignTrainHeadToDriverSeat(train, DriverControlEnd.TAIL)

        assertTrue(train.isReversed)
        assertFalse(outgoing.reverseRequested)
    }

    @Test
    fun `cab already at active head does not reverse train`() {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(80.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))

        alignTrainHeadToDriverSeat(train, DriverControlEnd.HEAD)

        assertFalse(train.isReversed)
    }

    private class ReverseTrackingController(train: Train, world: World) :
        TrainController(train, world) {
        var reverseRequested = false

        override fun reverse() {
            reverseRequested = true
            super.reverse()
        }
    }
}
