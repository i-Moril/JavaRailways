package work.art1st.javarailways.basic.safety

import work.art1st.javarailways.basic.rail.RailMetadata
import work.art1st.javarailways.basic.testing.TestBasicWorldDataRegistry
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.basic.safety.TrainSpeedProfile
import work.art1st.javarailways.basic.rail.infrastructureMetadata as metadata
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.CoreTrain
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld as createCoreTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig

class SpeedSafetyMonitorTest {
    private fun createTestWorld() = createCoreTestWorld().also { TestBasicWorldDataRegistry.getOrCreate(it) }

    @Test
    fun `exceeding operating limit alone does not derail`() {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(80.0, segmentCount = 1)
        )
        rail.metadata = RailMetadata(
            designSpeedMetersPerSecond = 15.0,
            speedLimitMetersPerSecond = 8.0,
            derailSpeedMetersPerSecond = 12.0
        )
        val train = createTrainWithConfig("T0", rail, world, listOf(8.0))
        FixedSpeedController(train, world).also {
            train.setController(it)
            it.publishSpeed(10.0)
        }
        val monitor = SpeedSafetyMonitor(
            world,
            SpeedSafetyMonitor.Config(true, 0.0, 1)
        )

        val requests = evaluate(monitor, train, 1L)

        assertTrue(requests.isEmpty())
        assertFalse(train.isDerailed)
    }

    @Test
    fun `derails only after configured grace ticks`() {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(80.0, segmentCount = 1)
        )
        rail.metadata = RailMetadata(
            designSpeedMetersPerSecond = 8.0,
            speedLimitMetersPerSecond = 8.0,
            derailSpeedMetersPerSecond = 10.0
        )
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

        assertTrue(evaluate(monitor, train, 1L).isEmpty())
        assertFalse(train.isDerailed)
        assertTrue(evaluate(monitor, train, 1L).isEmpty(), "same epoch must not consume grace twice")

        val requests = evaluate(monitor, train, 2L)

        assertEquals(1, requests.size)
        assertEquals(train.id, requests.single().trainId)
        assertEquals(2L, requests.single().observedPhysicsEpoch)
        assertFalse(train.isDerailed, "monitor returns a request; simulation owner applies it")
    }

    @Test
    fun `curve offset changes derail threshold but cannot exceed subgrade`() {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(80.0, segmentCount = 1)
        )
        rail.metadata = RailMetadata(
            designSpeedMetersPerSecond = 22.0,
            speedLimitMetersPerSecond = 18.0,
            derailSpeedMetersPerSecond = 20.0
        )
        val train = createTrainWithConfig("offset", rail, world, listOf(8.0))
        world.basicWorldData.trainSpeedProfiles.put(train.id, TrainSpeedProfile(5.0))
        FixedSpeedController(train, world).also {
            train.setController(it)
            it.publishSpeed(23.0)
        }
        val monitor = SpeedSafetyMonitor(world, SpeedSafetyMonitor.Config(true, 0.0, 1))

        val request = evaluate(monitor, train, 1L).single()

        assertTrue(request.reason.contains("22.00 m/s"), request.reason)
    }

    private fun evaluate(
        monitor: SpeedSafetyMonitor,
        train: CoreTrain,
        epoch: Long
    ): List<DerailRequest> {
        monitor.process(train, epoch)
        monitor.completeEpoch(setOf(train.id), epoch)
        return monitor.drainRequests(epoch)
    }

    private class FixedSpeedController(train: Train, world: World) : TrainController(train, world) {
        fun publishSpeed(value: Double) {
            speed = value
            commit()
        }
    }
}
