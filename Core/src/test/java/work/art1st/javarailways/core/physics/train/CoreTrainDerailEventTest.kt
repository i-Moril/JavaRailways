package work.art1st.javarailways.core.physics.train

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.event.DerailEvent
import work.art1st.javarailways.core.api.event.processor.EventListener
import work.art1st.javarailways.core.api.event.processor.HandlesEvent
import work.art1st.javarailways.core.api.event.processor.MainRailEventBus
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.util.IsolatedMainThreadMarker

class CoreTrainDerailEventTest {
    @Test
    fun `wrong-thread derail fails before changing train state`() {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0))
        val train = createTrainWithConfig("wrong-thread", rail, world, listOf(10.0))

        assertThrows(IllegalStateException::class.java) { train.derail("wrong thread") }

        assertFalse(train.isDerailed)
        assertFalse(train.publishedFrame.derailed)
        assertEquals(null, train.derailReason)
    }

    @Test
    fun `physics derail publishes one main event after publication`() {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0))
        val train = createTrainWithConfig("physics-event", rail, world, listOf(10.0))
        val listener = DerailCounter()
        val registration = MainRailEventBus.register(listener)
        try {
            train.markDerailedFromPhysics("physics failure")
            train.publishPhysicsState()
            val previousFrame = train.publishedFrame
            val frame = train.publishFrame(7L)
            if (!previousFrame.derailed && frame.derailed) train.queuePublishedDerailNotification()

            IsolatedMainThreadMarker.run { world.publishPendingDerailEvents() }
            IsolatedMainThreadMarker.run { world.publishPendingDerailEvents() }

            assertEquals(1, listener.count)
            assertEquals("physics failure", listener.reason)
            assertEquals(7L, train.publishedFrame.physicsEpoch)
        } finally {
            registration.close()
        }
    }

    private class DerailCounter : EventListener {
        var count = 0
        var reason: String? = null

        @HandlesEvent
        fun onDerail(event: DerailEvent) {
            count++
            reason = event.msg
        }
    }
}
