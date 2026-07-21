package work.art1st.mineRails.core.physics.train

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.mineRails.core.api.physics.rail.ViewDirection
import work.art1st.mineRails.core.di.NoOpWorldParentContext
import work.art1st.mineRails.core.physics.CoreWorld
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.mineRails.core.physics.tick.PhysicsCoordinator
import work.art1st.mineRails.core.util.ThreadGuard
import work.art1st.mineRails.core.util.ThreadGuardPlatformBridge
import work.art1st.mineRails.core.util.TickManagerImpl
import java.util.concurrent.Executors

class CoreTrainReversedPersistenceTest {
    @Test
    fun `reversed train restores its orientation and exact bogie locations`() {
        val source = createTestWorld()
        val rail = createStraightRailFromSegments(
            source,
            PrimeCurveGenerator.buildStraight(300.0, segmentCount = 1)
        )
        val train = createTrainWithConfig(
            id = "reversed",
            rail = rail,
            world = source,
            carLengths = listOf(18.0, 18.0, 18.0),
            isLoop = false
        )
        train.reverseOnMainThread()
        val expectedBogieLocations = train.cars.map { car ->
            car.bogies.map { bogie -> bogie.location.save() }
        }

        val infrastructure = source.saveInfrastructure(7L)
        val runtime = source.saveRuntime(7L, physicsEpoch = 11L)
        assertTrue(runtime.trains.single().reversed)

        val restoredTickManager = TickManagerImpl(Executors.newSingleThreadScheduledExecutor(), 0L)
        val restoredPhysics = PhysicsCoordinator(1)
        val restored = CoreWorld.load(
            infrastructure,
            runtime,
            CoreWorld.SnapshotDependency(
                restoredTickManager,
                NoOpWorldParentContext,
                restoredPhysics
            )
        )

        try {
            val restoredTrain = requireNotNull(restored.getTrain("reversed"))
            assertEquals(
                listOf("reversed"),
                restored.drainPublishedTrainFrames().map { it.trainId }
            )
            assertTrue(restoredTrain.isReversed)
            assertTrue(restoredTrain.cars.all(CoreCar::isReversed))
            assertEquals(
                expectedBogieLocations,
                restoredTrain.cars.map { car -> car.bogies.map { bogie -> bogie.location.save() } }
            )
        } finally {
            restoredPhysics.close()
            restoredTickManager.close()
            source.physicsCoordinator.close()
            (source.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `failed persisted placement leaves no partially registered train`() {
        val source = createTestWorld()
        val rail = createStraightRailFromSegments(
            source,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        createTrainWithConfig("invalid", rail, source, listOf(18.0), isLoop = false)
        val infrastructure = source.saveInfrastructure(9L)
        val savedTrain = source.saveRuntime(9L, physicsEpoch = 13L).trains.single()
        val savedCar = savedTrain.cars.single().let { car ->
            car.copy(config = car.config.copy(bogieAOffset = 5.75, bogieBOffset = -5.75))
        }
        val invalidHeadBogie = savedCar.bogieB.copy(
            location = savedCar.bogieB.location.copy(
                arcLengthInCurveAB = 0.0,
                direction = ViewDirection.BA
            )
        )
        val runtime = source.saveRuntime(9L, physicsEpoch = 13L).copy(
            trains = listOf(
                savedTrain.copy(
                    cars = listOf(savedCar.copy(bogieB = invalidHeadBogie)),
                    reversed = true
                )
            )
        )
        val restoredTickManager = TickManagerImpl(Executors.newSingleThreadScheduledExecutor(), 0L)
        val restoredPhysics = PhysicsCoordinator(1)
        val restored = CoreWorld.load(
            infrastructure,
            runtime,
            CoreWorld.SnapshotDependency(restoredTickManager, NoOpWorldParentContext, restoredPhysics)
        )

        try {
            assertNull(restored.getTrain("invalid"))
        } finally {
            restoredPhysics.close()
            restoredTickManager.close()
            source.physicsCoordinator.close()
            (source.tickManager as AutoCloseable).close()
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
