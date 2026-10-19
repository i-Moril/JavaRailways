package work.art1st.javarailways.core.physics.train

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import work.art1st.javarailways.core.api.event.TrainLinkEvent
import work.art1st.javarailways.core.api.event.TrainLinkSuccessEvent
import work.art1st.javarailways.core.api.event.processor.EventListener
import work.art1st.javarailways.core.api.event.processor.HandlesEvent
import work.art1st.javarailways.core.api.event.processor.MainRailEventBus
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.api.control.ControllerConsistState
import work.art1st.javarailways.core.api.control.PreparedControllerStateUpdate
import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.api.physics.train.Car
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.api.threading.host.ThreadGuardHost
import work.art1st.javarailways.core.api.threading.host.ThreadGuardPlatformBridge
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutionException

class CoreTrainLinkTest {
    @ParameterizedTest
    @ValueSource(strings = ["success", "veto", "prepare failure"])
    fun `collision link commits before notification and preserves sources on rejected preparation`(outcome: String) {
        val world = createTestWorld()
        try {
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(300.0))
            val lead = createTrainWithConfig("lead", rail, world, listOf(10.0), isLoop = false)
            val anchor = RailLocationWithFacing(rail.viewAB, 40.0)
            val follower = world.onOwnerForTest { CoreTrain("follower", listOf(CoreCar.create(
                "follower-car", CoreBogie.create(anchor), CoreBogie.create(anchor), length = 10.0,
                floorHeight = 0.0, connectorLengthAtA = 0.0, connectorLengthAtB = 0.0, world = world
            )), world, emptyMap()) }
            lead.world.onOwnerForTest {
                lead.setLocation(RailLocationWithFacing(rail.viewAB, 50.0))
                lead.commit()
            }
            follower.world.onOwnerForTest {
                follower.setLocation(RailLocationWithFacing(rail.viewAB, 40.0))
                follower.commit()
            }
            val movedCar = follower.cars.single()
            val oldStream = follower.publicationStreamId
            val preparationFailure = IllegalStateException("controller preparation failed")
            var preparationCalls = 0
            val controller = object : TrainController(lead, world) {
                val consist = ControllerConsistState<List<Car>>(lead.cars.toList())
                val handle = "P2"
                override fun prepareConsistChange(cars: List<Car>, counterpart: TrainController?): List<PreparedControllerStateUpdate> {
                    preparationCalls++
                    assertEquals(1, lead.cars.size)
                    assertSame(follower, movedCar.train)
                    assertEquals(1, consist.value.size)
                    if (outcome == "prepare failure") throw preparationFailure
                    return listOf(consist.prepare(cars.toList()))
                }
            }
            lead.setControllerForTest(controller)

            assertTrue(movedCar.isOwnedByPublicationStream(oldStream))
            assertSame(follower, movedCar.train)
            assertEquals(2, world.allTrains.size)
            assertEquals(4, rail.trains.size)

            val originalLeadFrame = lead.publishedFrame
            val originalFollowerFrame = follower.publishedFrame
            val originalEnds = rail.trains.toList()
            var successFrame: TrainFrameSnapshot? = null
            var successCalls = 0
            var trainsAtSuccess: List<String>? = null
            var ownersAtSuccess: List<String?>? = null
            val result = world.physicsCoordinator.submitBetweenFrames {
                lead.linkFromCollisionOnOwner(follower, originalLeadFrame, originalFollowerFrame,
                    world.topologyRevision, world.collisionEnvelope(lead.id), world.collisionEnvelope(follower.id))
            }
            val owner = Thread.currentThread()
            ThreadGuardHost.registerPlatformBridge(object : ThreadGuardPlatformBridge {
                override fun isMainThread(): Boolean = Thread.currentThread() === owner
            }).use {
                MainRailEventBus.register(object : EventListener {
                    @HandlesEvent
                    fun decide(event: TrainLinkEvent) {
                        if (outcome == "veto") event.reject("fixed unit")
                    }

                    @HandlesEvent
                    fun succeeded(event: TrainLinkSuccessEvent) {
                        successCalls++
                        successFrame = event.train.publishedFrame
                        trainsAtSuccess = world.allTrains.map { it.id }
                        ownersAtSuccess = event.train.cars.map { it.train?.id }
                    }
                }).use { world.onOwnerForTest { world.physicsCoordinator.drainBetweenFramesOperations() } }
            }
            if (outcome != "success") {
                val failure = assertThrows(ExecutionException::class.java) { result.get(5, TimeUnit.SECONDS) }
                assertTrue(failure.cause is IllegalStateException)
                if (outcome == "prepare failure") assertSame(preparationFailure, failure.cause)
                assertEquals(if (outcome == "veto") 0 else 1, preparationCalls)
                assertEquals(0, successCalls)
                assertSame(originalLeadFrame, lead.publishedFrame)
                assertSame(originalFollowerFrame, follower.publishedFrame)
                assertEquals(originalEnds, rail.trains.toList())
                assertEquals(setOf("lead", "follower"), world.allTrains.map { it.id }.toSet())
                assertSame(controller, lead.controller)
                assertEquals(listOf(lead.cars.single()), controller.consist.value)
                assertSame(follower, movedCar.train)
                assertTrue(movedCar.isOwnedByPublicationStream(oldStream))
                return
            }
            assertSame(lead, result.get(5, TimeUnit.SECONDS))
            assertEquals(1, preparationCalls)
            assertEquals(1, successCalls)
            assertSame(lead.publishedFrame, successFrame)
            assertEquals(listOf("lead"), trainsAtSuccess)
            assertEquals(listOf("lead", "lead"), ownersAtSuccess)
            assertEquals(lead.cars.map { it.id }, successFrame!!.cars.map { it.carId })
            assertEquals(originalLeadFrame.head, successFrame!!.head)
            assertEquals(originalFollowerFrame.tail, successFrame!!.tail)
            assertEquals(originalLeadFrame.physicsEpoch, successFrame!!.physicsEpoch)
            assertSame(controller, lead.controller)
            assertEquals("P2", controller.handle)
            assertEquals(lead.cars, controller.consist.value)

            assertFalse(movedCar.isOwnedByPublicationStream(oldStream))
            assertTrue(movedCar.isOwnedByPublicationStream(lead.publicationStreamId))
            assertSame(lead, movedCar.train)
            world.onOwnerForTest { world.unregisterTrain(follower) }
            assertSame(lead, movedCar.train, "retiring the old owner must not clear transferred cars")
            assertEquals(listOf("lead"), world.allTrains.map { it.id })
            assertEquals(2, rail.trains.size)
            rail.trains.forEach { end ->
                assertSame(lead, end.trainOrNull)
            }
        } finally {
            world.physicsCoordinator.close()
            world.releaseRuntimeBinding()
            (world.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `removing a train retires its car publication ownership`() {
        val world = createTestWorld()
        try {
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0))
            val train = createTrainWithConfig("removed", rail, world, listOf(10.0), isLoop = false)
            val car = train.cars.single()

            assertTrue(car.isOwnedByPublicationStream(train.publicationStreamId))
            assertSame(train, car.train)
            world.onOwnerForTest { world.unregisterTrain(train) }
            assertFalse(car.isOwnedByPublicationStream(train.publicationStreamId))
            assertNull(car.train)
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `repeated removal of an old instance does not remove its same-id replacement`() {
        val world = createTestWorld()
        try {
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0))
            val old = createTrainWithConfig("reused", rail, world, listOf(10.0), isLoop = false)
            world.onOwnerForTest { world.unregisterTrain(old) }
            val replacement = createTrainWithConfig("reused", rail, world, listOf(10.0), isLoop = false)
            world.publishCurrentTrainFrame(replacement.id)

            world.onOwnerForTest { world.unregisterTrain(old) }

            assertSame(replacement, world.getTrain("reused"))
            assertSame(replacement.publishedFrame, world.drainPublishedTrainFrames().single())
            assertTrue(replacement.cars.single().isOwnedByPublicationStream(replacement.publicationStreamId))
            assertSame(replacement, replacement.cars.single().train)
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }
}
