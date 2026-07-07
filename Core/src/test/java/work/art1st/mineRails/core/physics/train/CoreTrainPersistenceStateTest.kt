package work.art1st.mineRails.core.physics.train

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.mineRails.core.control.train.MockTrainController
import work.art1st.mineRails.core.di.NoOpWorldParentContext
import work.art1st.mineRails.core.physics.CoreWorld
import work.art1st.mineRails.core.physics.rail.RailLocationWithFacing
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.mineRails.core.util.TickManagerImpl
import java.util.concurrent.Executors

class CoreTrainPersistenceStateTest {
    @Test
    fun `zero distance controller tick does not toggle car position back to default`() {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0, segmentCount = 1))
        val train = createTrainWithConfig("stable", rail, world, listOf(10.0), isLoop = false)
        train.setLocation(RailLocationWithFacing(rail.viewAB, 80.0))
        train.commit()
        val expected = Vector3d(train.cars.first().position)

        train.setController(MockTrainController(train, world, 0.0))
        world.tickManager.postTick()
        repeat(6) { tick ->
            world.tickManager.preTick(System.currentTimeMillis() + tick * 50L)
            world.tickManager.postTick()
            assertEquals(0.0, train.cars.first().position.distance(expected), 1e-9, "tick $tick")
        }
    }

    @Test
    fun `saved train load reconstructs committed car positions`() {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0, segmentCount = 1))
        val train = createTrainWithConfig("persisted", rail, world, listOf(10.0), isLoop = false)
        train.setLocation(RailLocationWithFacing(rail.viewAB, 80.0))
        train.commit()
        val expected = Vector3d(train.cars.first().position)

        val loaded = world.save().load(
            CoreWorld.SavedWorldV1.Dependency(
                TickManagerImpl(Executors.newSingleThreadScheduledExecutor(), System.currentTimeMillis()),
                NoOpWorldParentContext
            )
        )
        val loadedTrain = loaded.getTrain("persisted") ?: error("train should load")
        val actual = loadedTrain.cars.first().position

        assertTrue(expected.lengthSquared() > 1.0, "expected test train to be away from origin")
        assertEquals(0.0, actual.distance(expected), 1e-9)
    }
}
