package work.art1st.mineRails.basic.safety

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.mineRails.core.api.control.TrainController
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.physics.rail.RailMetadata
import work.art1st.mineRails.core.api.physics.train.Train
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.mineRails.core.physics.train.CoreTrain
import work.art1st.mineRails.core.physics.train.createStraightRailFromSegments
import work.art1st.mineRails.core.physics.train.createTestWorld
import work.art1st.mineRails.core.physics.train.createTrainWithConfig

class SpeedSafetyMonitorTest {
    @Test
    fun `derails only after configured grace ticks`() {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(80.0, segmentCount = 1)
        )
        rail.metadata = RailMetadata(designSpeedMetersPerSecond = 10.0)
        val train = createTrainWithConfig("T1", rail, world, listOf(8.0))
        FixedSpeedController(train, world).also {
            train.setController(it)
            it.publishSpeed(12.0)
        }
        val monitor = SpeedSafetyMonitor(
            world,
            SpeedSafetyMonitor.Config(
                derailEnabled = true,
                toleranceMetersPerSecond = 1.0,
                graceTicks = 2
            )
        )

        monitor.tick(50.0)
        assertFalse(train.isDerailed)
        monitor.tick(50.0)
        assertTrue(train.isDerailed)
    }

    private class FixedSpeedController(train: Train, world: World) : TrainController(train, world) {
        fun publishSpeed(value: Double) {
            speed = value
            commit()
        }
    }
}
