package work.art1st.javarailways.core.physics.train

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.event.DerailEvent
import work.art1st.javarailways.core.api.event.processor.EventListener
import work.art1st.javarailways.core.api.event.processor.HandlesEvent
import work.art1st.javarailways.core.api.event.processor.MainRailEventBus
import work.art1st.javarailways.core.api.physics.exception.BogieException
import work.art1st.javarailways.core.control.train.MockTrainController
import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.physics.CoreWorld
import java.util.concurrent.TimeUnit

class CoreTrainDerailEventTest {
    @Test
    fun `external derail waits for owner and notifies only after coherent stationary publication`() {
        val coordinator = PhysicsCoordinator(1, automaticFallback = false)
        val world = createTestWorld(coordinator)
        val listener = DerailCounter()
        val registration = MainRailEventBus.register(listener)
        try {
            val rail = IsolatedSimulationThreadMarker.run {
                createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0))
            }
            val train = createTrainWithConfig("external-event", rail, world, listOf(10.0))
            train.setControllerForTest(MockTrainController(train, constantSpeed = 10.0))
            coordinator.start()
            val ticket = coordinator.stepSimulation(50.0)
            ticket.completion.get(5, TimeUnit.SECONDS)
            val previous = train.publishedFrame
            val previousWorld = world.latestWorldFrame()
            assertTrue(previous.cars.single().state.velocityX > 0.0)

            val request = train.derailAsync("external request")
            assertFalse(request.isDone)
            assertFalse(train.isDerailed)
            assertSame(previous, train.publishedFrame)
            assertSame(previousWorld, world.latestWorldFrame())
            world.onOwnerForTest { coordinator.drainBetweenFramesOperations() }
            request.get(5, TimeUnit.SECONDS)

            assertEquals(1, listener.count)
            assertEquals("external request", listener.reason)
            assertEquals(ticket.epoch, listener.publishedEpoch)
            assertTrue(listener.coherentWorld)
            assertTrue(listener.stationary)
            assertEquals(null, train.controller)
            assertFalse(previous.derailed)
            assertSame(previous, previousWorld.trains.single())
            val repeated = train.derailAsync("already derailed")
            world.onOwnerForTest { coordinator.drainBetweenFramesOperations() }
            repeated.get(5, TimeUnit.SECONDS)
            coordinator.stepSimulation(50.0).completion.get(5, TimeUnit.SECONDS)
            IsolatedSimulationThreadMarker.run { world.publishPendingDerailEvents() }
            assertEquals(1, listener.count)
            assertTrue(train.publishedFrame.cars.all { it.state.velocityX == 0.0 })
        } finally {
            registration.close()
            coordinator.close()
            (world.tickManager as? AutoCloseable)?.close()
        }
    }

    @Test
    fun `wrong-thread derail fails before changing train state`() {
        val world = createTestWorld()
        try {
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0))
            val train = createTrainWithConfig("wrong-thread", rail, world, listOf(10.0))

            assertThrows(IllegalStateException::class.java) { train.derail("wrong thread") }

            assertFalse(train.isDerailed)
            assertFalse(train.publishedFrame.derailed)
            assertEquals(null, train.derailReason)
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as? AutoCloseable)?.close()
        }
    }

    @Test
    fun `physics derail publishes one main event after publication`() {
        val coordinator = PhysicsCoordinator(1, automaticFallback = false)
        val world = createTestWorld(coordinator)
        try {
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0))
            val train = createTrainWithConfig("physics-event", rail, world, listOf(10.0))
            val initialHead = train.headLocation
            val initialTail = train.tailLocation
            val initialBogieLocation = train.headBogie.location
            // A 100 m movement runs the head bogie beyond the unconnected end of this rail.
            train.setControllerForTest(MockTrainController(train, constantSpeed = 2000.0))
            val listener = DerailCounter()
            val registration = MainRailEventBus.register(listener)
            try {
                coordinator.start()
                val ticket = coordinator.stepSimulation(50.0)
                ticket.completion.get(5, TimeUnit.SECONDS)

                assertTrue(train.isDerailed)
                assertTrue(train.publishedFrame.derailed)
                val reason = requireNotNull(train.derailReason)
                assertTrue(reason.contains("train=${train.id}"), reason)
                assertTrue(reason.contains("deltaSMeters=100.0"), reason)
                assertTrue(reason.contains(BogieException::class.java.name), reason)
                assertTrue(reason.contains("published rail=${rail.id}"), reason)
                assertTrue(reason.contains("facing=${initialBogieLocation.facing}"), reason)
                assertTrue(reason.contains("arcLengthMeters=${initialBogieLocation.arcLength}"), reason)
                assertEquals(ticket.epoch, train.publishedFrame.physicsEpoch)
                assertEquals(initialHead, train.headLocation)
                assertEquals(initialTail, train.tailLocation)
                assertEquals(null, train.controller)
                assertEquals(0, listener.count, "physics publication must not dispatch a main event")

                IsolatedSimulationThreadMarker.run { world.publishPendingDerailEvents() }

                assertEquals(1, listener.count)
                assertEquals(train.derailReason, listener.reason)
                assertEquals(ticket.epoch, listener.publishedEpoch)
                assertTrue(listener.publishedDerailed)
                assertTrue(listener.publicDerailed)

                IsolatedSimulationThreadMarker.run { world.publishPendingDerailEvents() }
                coordinator.stepSimulation(50.0).completion.get(5, TimeUnit.SECONDS)
                IsolatedSimulationThreadMarker.run { world.publishPendingDerailEvents() }
                assertEquals(1, listener.count, "draining or publishing another epoch must not repeat the event")
            } finally {
                registration.close()
            }
        } finally {
            coordinator.close()
            (world.tickManager as? AutoCloseable)?.close()
        }
    }

    private class DerailCounter : EventListener {
        var count = 0
        var reason: String? = null
        var publishedEpoch: Long? = null
        var publishedDerailed = false
        var publicDerailed = false
        var coherentWorld = false
        var stationary = false

        @HandlesEvent
        fun onDerail(event: DerailEvent) {
            count++
            reason = event.msg
            val frame = event.train.publishedFrame
            publishedEpoch = frame?.physicsEpoch
            publishedDerailed = frame?.derailed == true
            publicDerailed = event.train.isDerailed
            val world = event.train.world as CoreWorld
            val worldFrame = world.latestWorldFrame()
            coherentWorld = frame != null && worldFrame.trains.any { it === frame } &&
                worldFrame.physicsEpoch == frame.physicsEpoch &&
                worldFrame.occupancy.blockingTrainIds(frame.occupiedIntervals).contains(event.train.id)
            stationary = frame?.cars?.all { car ->
                car.state.velocityX == 0.0 && car.state.velocityY == 0.0 && car.state.velocityZ == 0.0 &&
                    car.bogies.all { it.velocityX == 0.0 && it.velocityY == 0.0 && it.velocityZ == 0.0 }
            } == true
        }
    }
}
