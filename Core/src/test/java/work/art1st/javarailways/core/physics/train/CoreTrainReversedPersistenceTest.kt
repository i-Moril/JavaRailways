package work.art1st.javarailways.core.physics.train

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.threading.host.ThreadGuardHost
import work.art1st.javarailways.core.api.threading.host.ThreadGuardPlatformBridge
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.util.TickManagerImpl
import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CoreTrainReversedPersistenceTest {
    @Test
    fun `reversed train restores its orientation and exact bogie locations`() {
        withWorld { source ->
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

            withWorld(load = { tick, physics ->
                IsolatedSimulationThreadMarker.run { CoreWorld.load(infrastructure, runtime, CoreWorld.SnapshotDependency(tick, physics)) }
            }) { restored ->
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
                val frame = restoredTrain.publishedFrame
                assertEquals(ViewDirection.BA, frame.head.facing)
                assertEquals(ViewDirection.BA, frame.tail.facing)
                // Geometric placement uses Curve.TOLERANCE; saved bogies above must still match exactly.
                assertEquals(10.0, frame.head.arcLengthInAbMeters, Curve.TOLERANCE)
                assertEquals(64.0, frame.tail.arcLengthInAbMeters, Curve.TOLERANCE)
                listOf(55.0, 37.0, 19.0).zip(frame.cars).forEach { (expectedX, car) ->
                    assertEquals(expectedX, car.state.positionX, Curve.TOLERANCE)
                }
            }
        }
    }

    @Test
    fun `failed persisted placement leaves no partially registered train`() {
        withWorld { world ->
            val rail = createStraightRailFromSegments(
                world,
                PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
            )
            val train = createTrainWithConfig("invalid", rail, world, listOf(18.0), isLoop = false)
            world.onOwnerForTest { world.renameTrain(train, "Invalid Local") }
            val saved = train.save()
            val savedCar = saved.cars.single().let { car ->
                car.copy(config = car.config.copy(bogieAOffset = 5.75, bogieBOffset = -5.75))
            }
            val invalidHeadBogie = savedCar.bogieB.copy(
                location = savedCar.bogieB.location.copy(
                    arcLengthInCurveAB = 0.0,
                    direction = ViewDirection.BA
                )
            )
            val invalid = saved.copy(
                cars = listOf(savedCar.copy(bogieB = invalidHeadBogie)),
                reversed = true
            )
            world.onOwnerForTest { train.destroy() }
            assertTrue(world.allTrains.isEmpty())
            world.drainPublishedTrainFrames()

            val failure = assertThrows(IllegalStateException::class.java) {
                world.onOwnerForTest { invalid.load(world) }
            }

            assertTrue(failure.message!!.contains("invalid"))
            assertTrue(world.allTrains.isEmpty())
            assertNull(world.getTrain(saved.id))
            assertNull(world.getTrain(saved.name))
            assertTrue(world.allRails.single().trains.isEmpty())
            assertTrue(world.drainPublishedTrainFrames().isEmpty())
        }
    }

    private fun withWorld(
        load: (TickManagerImpl, PhysicsCoordinator) -> CoreWorld = { tick, physics ->
            CoreWorld.createEmpty(tick, physics)
        },
        block: (CoreWorld) -> Unit
    ) {
        TickManagerImpl(Executors.newSingleThreadScheduledExecutor(), 0L).use { tick ->
            run {
                PhysicsCoordinator(1, automaticFallback = false).use { physics ->
                    val created = load(tick, physics)
                    block(created)
                }
            }
        }
    }

    private fun CoreTrain.reverseOnMainThread() {
        val owner = Thread.currentThread()
        val registration = ThreadGuardHost.registerPlatformBridge(object : ThreadGuardPlatformBridge {
            override fun isMainThread(): Boolean = Thread.currentThread() === owner
        })
        try {
            reverse()
        } finally {
            registration.close()
        }
    }
}
