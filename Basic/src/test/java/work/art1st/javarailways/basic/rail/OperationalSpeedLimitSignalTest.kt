package work.art1st.javarailways.basic.rail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.testing.TestBasicWorldDataRegistry
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.basic.safety.TrainSpeedProfile
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig

class OperationalSpeedLimitSignalTest {
    @Test
    fun `materializes one direction-filtered entry signal per RailView`() {
        val world = createTestWorld()
        TestBasicWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(40.0, segmentCount = 1)
        )
        rail.infrastructureMetadata = RailInfrastructureMetadata(
            speedLimits = SpeedLimitMetadata(
                listOf(
                    SpeedLimitEntry(30.0, SpeedLimitType.SUBGRADE),
                    SpeedLimitEntry(20.0, SpeedLimitType.CURVE)
                )
            )
        )
        val train = createTrainWithConfig("signal-test", rail, world, listOf(8.0))
        world.basicWorldData.trainSpeedProfiles.put(train.id, TrainSpeedProfile(10.0))

        OperationalSpeedLimitSignalMaterializer.materialize(world)

        val ab = rail.viewAB.staticRailObjects.single() as OperationalSpeedLimitSignal
        val ba = rail.viewBA.staticRailObjects.single() as OperationalSpeedLimitSignal
        assertEquals(ViewDirection.AB, ab.appliesTo)
        assertEquals(ViewDirection.BA, ba.appliesTo)
        assertEquals(0.0, ab.location.arcLengthIn(ViewDirection.AB))
        assertEquals(0.0, ba.location.arcLengthIn(ViewDirection.BA))
        assertEquals(20.0, ab.getSpeedLimit(train))
        assertEquals(20.0, ba.getSpeedLimit(train))
        assertTrue(ab !in rail.viewBA.staticRailObjects)
        assertTrue(ba !in rail.viewAB.staticRailObjects)
    }

    @Test
    fun `empty metadata creates release transitions`() {
        val world = createTestWorld()
        TestBasicWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(40.0, segmentCount = 1)
        )

        OperationalSpeedLimitSignalMaterializer.materialize(world)

        val signal = rail.viewAB.staticRailObjects.single() as OperationalSpeedLimitSignal
        assertNull(signal.getSpeedLimit(createTrainWithConfig("release-test", rail, world, listOf(8.0))))
    }
}
