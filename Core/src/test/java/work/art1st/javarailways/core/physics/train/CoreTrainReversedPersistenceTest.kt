package work.art1st.javarailways.core.physics.train

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.threading.host.ThreadGuardHost
import work.art1st.javarailways.core.api.threading.host.ThreadGuardPlatformBridge
import work.art1st.javarailways.core.di.NoOpWorldParentContext
import work.art1st.javarailways.core.di.WorldContextRegistration
import work.art1st.javarailways.core.di.WorldParentContext
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
                CoreWorld.load(infrastructure, runtime, CoreWorld.SnapshotDependency(tick, NoOpWorldParentContext, physics))
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
        withWorld { source ->
            val rail = createStraightRailFromSegments(
                source,
                PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
            )
            val train = createTrainWithConfig("invalid", rail, source, listOf(18.0), isLoop = false)
            IsolatedSimulationThreadMarker.run {
                source.physicsCoordinator.processBetweenFrames {
                    source.renameTrain(train, "Invalid Local")
                }
            }
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
            var failedWorld: CoreWorld? = null
            var bindingClosed = false
            val parent = WorldParentContext { world ->
                failedWorld = world
                WorldContextRegistration { bindingClosed = true }
            }
            TickManagerImpl(Executors.newSingleThreadScheduledExecutor(), 0L).use { tick ->
                AutoCloseable { failedWorld?.releaseRuntimeBinding() }.use {
                    PhysicsCoordinator(1, automaticFallback = false).use { physics ->
                        val failure = assertThrows(IllegalStateException::class.java) {
                            CoreWorld.load(
                                infrastructure,
                                runtime,
                                CoreWorld.SnapshotDependency(tick, parent, physics)
                            )
                        }
                        assertTrue(failure.message!!.contains("invalid"))
                        assertNotNull(failure.cause)
                        assertTrue(bindingClosed, "failed load must release its runtime binding")
                        val target = requireNotNull(failedWorld)
                        assertTrue(target.allTrains.isEmpty())
                        assertNull(target.getTrain(savedTrain.id))
                        assertNull(target.getTrain(savedTrain.name))
                        assertTrue(target.allRails.single().trains.isEmpty())
                        assertTrue(target.drainPublishedTrainFrames().isEmpty())
                        physics.start()
                        val ticket = physics.stepSimulation(50.0)
                        ticket.completion.get(5, TimeUnit.SECONDS)
                        val frame = target.latestWorldFrame()
                        assertEquals(ticket.epoch, frame.physicsEpoch)
                        assertTrue(frame.trains.isEmpty())
                        assertTrue(frame.occupancy.trainIds.isEmpty())
                        assertTrue(target.drainPublishedTrainFrames().isEmpty())
                    }
                }
            }
        }
    }

    private fun withWorld(
        load: (TickManagerImpl, PhysicsCoordinator) -> CoreWorld = { tick, physics ->
            CoreWorld.createEmpty(tick, NoOpWorldParentContext, physics)
        },
        block: (CoreWorld) -> Unit
    ) {
        TickManagerImpl(Executors.newSingleThreadScheduledExecutor(), 0L).use { tick ->
            var world: CoreWorld? = null
            AutoCloseable { world?.releaseRuntimeBinding() }.use {
                PhysicsCoordinator(1, automaticFallback = false).use { physics ->
                    val created = load(tick, physics)
                    world = created
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
