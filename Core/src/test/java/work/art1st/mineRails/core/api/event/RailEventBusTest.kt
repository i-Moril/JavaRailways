package work.art1st.mineRails.core.api.event

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.mineRails.core.api.event.processor.EventListener
import work.art1st.mineRails.core.api.event.processor.HandlesEvent
import work.art1st.mineRails.core.api.event.processor.MainRailEventBus
import work.art1st.mineRails.core.api.event.processor.PhysicsRailEventBus
import work.art1st.mineRails.core.api.event.processor.Priority
import work.art1st.mineRails.core.api.util.annotation.AsyncEventApi
import work.art1st.mineRails.core.util.AsyncTickThreadMarker
import work.art1st.mineRails.core.util.ThreadGuard
import work.art1st.mineRails.core.util.ThreadGuardPlatformBridge

@OptIn(AsyncEventApi::class)
class RailEventBusTest {
    interface MockMainInterfaceA : MainThreadRailEvent
    interface MockMainInterfaceB : MainThreadRailEvent
    abstract class MockMainClass : MockMainInterfaceA
    class MockMainA : MockMainClass()
    class MockMainB : MockMainInterfaceB
    class MockMainC : MockMainInterfaceA, MockMainInterfaceB
    class MockPhysicsEvent : PhysicsRailEvent

    private inner class MockEventListener : EventListener {
        var activated: Int = 0
        var physicsCount: Int = 0

        @HandlesEvent
        fun handleInterfaceA(event: MockMainInterfaceA) {
            activated = activated or BIT_MAIN_INTERFACE_A
        }

        @HandlesEvent
        fun handleInterfaceB(event: MockMainInterfaceB) {
            activated = activated or BIT_MAIN_INTERFACE_B
        }

        @HandlesEvent
        fun handleClass(event: MockMainClass) {
            activated = activated or BIT_MAIN_CLASS
        }

        @HandlesEvent
        fun handleA(event: MockMainA) {
            activated = activated or BIT_MAIN_A
        }

        @HandlesEvent
        fun handleB(event: MockMainB) {
            activated = activated or BIT_MAIN_B
        }

        @HandlesEvent
        fun handleC(event: MockMainC) {
            activated = activated or BIT_MAIN_C
        }

        @HandlesEvent
        fun handlePhysics(event: MockPhysicsEvent) {
            physicsCount++
        }
    }

    @Test
    fun `main bus dispatches event inheritance and remains segregated from physics bus`() {
        val listener = MockEventListener()
        val mainRegistration = MainRailEventBus.register(listener)
        val physicsRegistration = PhysicsRailEventBus.register(listener)
        try {
            withMainThread {
                MainRailEventBus.publish(MockMainA())
                assertEquals(BIT_MAIN_A or BIT_MAIN_CLASS or BIT_MAIN_INTERFACE_A, listener.activated)

                listener.activated = 0
                MainRailEventBus.publish(MockMainB())
                assertEquals(BIT_MAIN_B or BIT_MAIN_INTERFACE_B, listener.activated)

                listener.activated = 0
                MainRailEventBus.publish(MockMainC())
                assertEquals(BIT_MAIN_C or BIT_MAIN_INTERFACE_A or BIT_MAIN_INTERFACE_B, listener.activated)
            }
            assertEquals(0, listener.physicsCount)

            AsyncTickThreadMarker.runInAsyncTickContext {
                PhysicsRailEventBus.publish(MockPhysicsEvent())
            }
            assertEquals(1, listener.physicsCount)
        } finally {
            mainRegistration.close()
            physicsRegistration.close()
            ThreadGuard.clearPlatformBridge()
        }
    }

    @Test
    fun `registrations are idempotently closeable`() {
        val listener = MockEventListener()
        val registration = MainRailEventBus.register(listener)
        assertFalse(registration.isClosed)
        registration.close()
        registration.close()
        assertTrue(registration.isClosed)

        withMainThread {
            MainRailEventBus.publish(MockMainA())
        }
        assertEquals(0, listener.activated)
    }

    @Test
    fun `typed buses reject the wrong thread context`() {
        assertThrows(IllegalStateException::class.java) {
            MainRailEventBus.publish(MockMainA())
        }
        assertThrows(IllegalStateException::class.java) {
            PhysicsRailEventBus.publish(MockPhysicsEvent())
        }
    }

    @Test
    fun `listener failures are isolated`() {
        var completed = false
        val listener = object : EventListener {
            @HandlesEvent(Priority.HIGHEST)
            fun fail(event: MockMainA) {
                error("expected listener failure")
            }

            @HandlesEvent(Priority.LOWEST)
            fun complete(event: MockMainA) {
                completed = true
            }
        }
        val registration = MainRailEventBus.register(listener)
        try {
            withMainThread {
                MainRailEventBus.publish(MockMainA())
            }
            assertTrue(completed)
        } finally {
            registration.close()
        }
    }

    private fun withMainThread(block: () -> Unit) {
        val owner = Thread.currentThread()
        ThreadGuard.installPlatformBridge(object : ThreadGuardPlatformBridge {
            override fun isMainThread(): Boolean = Thread.currentThread() === owner
        })
        try {
            block()
        } finally {
            ThreadGuard.clearPlatformBridge()
        }
    }

    private companion object {
        const val BIT_MAIN_INTERFACE_A = 0x01
        const val BIT_MAIN_CLASS = 0x02
        const val BIT_MAIN_A = 0x04
        const val BIT_MAIN_INTERFACE_B = 0x10
        const val BIT_MAIN_B = 0x20
        const val BIT_MAIN_C = 0x40
    }
}
