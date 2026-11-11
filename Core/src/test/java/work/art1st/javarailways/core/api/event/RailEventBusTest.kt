package work.art1st.javarailways.core.api.event

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.event.processor.EventListener
import work.art1st.javarailways.core.api.event.processor.HandlesEvent
import work.art1st.javarailways.core.api.event.processor.MainRailEventBus
import work.art1st.javarailways.core.api.event.processor.PhysicsRailEventBus
import work.art1st.javarailways.core.api.event.processor.Priority
import work.art1st.javarailways.core.api.event.processor.EventRegistration
import work.art1st.javarailways.core.api.event.processor.TypedRailEventBus
import work.art1st.javarailways.core.api.util.annotation.AsyncEventApi
import work.art1st.javarailways.core.util.AsyncTickThreadMarker
import work.art1st.javarailways.core.api.threading.ThreadGuard
import work.art1st.javarailways.core.api.threading.host.ThreadGuardHost
import work.art1st.javarailways.core.api.threading.host.ThreadGuardPlatformBridge
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(AsyncEventApi::class)
class RailEventBusTest {
    interface MockMainInterfaceA : MainThreadRailEvent
    interface MockMainInterfaceB : MainThreadRailEvent
    abstract class MockMainClass : MockMainInterfaceA
    class MockMainA : MockMainClass()
    class MockMainB : MockMainInterfaceB
    class MockMainC : MockMainInterfaceA, MockMainInterfaceB
    class MockPhysicsEvent : PhysicsRailEvent

    private class CountingListener : EventListener {
        val calls = AtomicInteger()

        @HandlesEvent
        fun handle(event: MockMainA) {
            calls.incrementAndGet()
        }
    }

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
        }
    }

    @Test
    fun `priority orders handlers across event inheritance levels`() {
        val order = mutableListOf<String>()
        val listener = object : EventListener {
            @HandlesEvent(Priority.LOWEST)
            fun concrete(event: MockMainA) {
                order += "concrete-lowest"
            }

            @HandlesEvent(Priority.HIGHEST)
            fun base(event: MockMainInterfaceA) {
                order += "base-highest"
            }
        }
        MainRailEventBus.register(listener).use {
            withMainThread { MainRailEventBus.publish(MockMainA()) }
        }
        assertEquals(listOf("base-highest", "concrete-lowest"), order)
    }

    @Test
    fun `malformed event handler signatures fail registration`() {
        val missingParameter = object : EventListener {
            @HandlesEvent
            fun handle() = Unit
        }
        assertThrows(IllegalArgumentException::class.java) {
            MainRailEventBus.register(missingParameter)
        }
        val notAnEvent = object : EventListener {
            @HandlesEvent
            @Suppress("UNUSED_PARAMETER")
            fun handle(value: String) = Unit
        }
        assertThrows(IllegalArgumentException::class.java) {
            MainRailEventBus.register(notAnEvent)
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

    @Test
    fun `concurrent registration survives unrelated close and unregister of the same event type`() {
        val bus = TypedRailEventBus(MainThreadRailEvent::class, "test")
        val executor = Executors.newFixedThreadPool(3)
        AutoCloseable {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "registration workers must stop")
        }.use {
            repeat(100) {
                val closing = CountingListener()
                val unregistering = CountingListener()
                val live = CountingListener()
                bus.register(closing).use { closingToken ->
                    bus.register(unregistering).use {
                        val start = CyclicBarrier(3)
                        val registration = executor.submit<EventRegistration> {
                            start.await(5, TimeUnit.SECONDS)
                            bus.register(live)
                        }
                        val close = executor.submit {
                            start.await(5, TimeUnit.SECONDS)
                            closingToken.close()
                        }
                        val unregister = executor.submit {
                            start.await(5, TimeUnit.SECONDS)
                            bus.unregister(unregistering)
                        }
                        registration.get(5, TimeUnit.SECONDS).use { liveToken ->
                            close.get(5, TimeUnit.SECONDS)
                            unregister.get(5, TimeUnit.SECONDS)
                            assertFalse(liveToken.isClosed)
                            bus.publish(MockMainA())
                            assertEquals(1, live.calls.get())
                            assertEquals(0, closing.calls.get())
                            assertEquals(0, unregistering.calls.get())
                        }
                        bus.publish(MockMainA())
                        assertEquals(1, live.calls.get())
                    }
                }
            }
        }
    }

    @Test
    fun `closing an old token preserves later registrations of the same listener`() {
        val bus = TypedRailEventBus(MainThreadRailEvent::class, "test")
        val listener = CountingListener()
        bus.register(listener).use { old ->
            bus.register(listener).use { current ->
                old.close()
                bus.publish(MockMainA())
                assertEquals(1, listener.calls.get())
                bus.unregister(listener)
                assertFalse(current.isClosed)
                bus.publish(MockMainA())
                assertEquals(1, listener.calls.get())
                bus.register(listener).use {
                    current.close()
                    old.close()
                    bus.publish(MockMainA())
                    assertEquals(2, listener.calls.get())
                }
            }
        }
    }

    @Test
    fun `callback can close itself and await registration on another thread`() {
        val bus = TypedRailEventBus(MainThreadRailEvent::class, "test")
        val replacement = CountingListener()
        val executor = Executors.newSingleThreadExecutor()
        AutoCloseable {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "registration worker must stop")
        }.use {
            lateinit var originalToken: EventRegistration
            var replacementToken: EventRegistration? = null
            var originalCalls = 0
            val original = object : EventListener {
                @HandlesEvent
                fun handle(event: MockMainA) {
                    originalCalls++
                    originalToken.close()
                    replacementToken = executor.submit<EventRegistration> {
                        bus.register(replacement)
                    }.get(5, TimeUnit.SECONDS)
                }
            }
            originalToken = bus.register(original)
            try {
                bus.publish(MockMainA())
                assertTrue(originalToken.isClosed)
                assertTrue(replacementToken != null, "callback must complete registration without blocking on publish")
                assertEquals(0, replacement.calls.get())
                bus.publish(MockMainA())
                assertEquals(1, originalCalls)
                assertEquals(1, replacement.calls.get())
            } finally {
                originalToken.close()
                replacementToken?.close()
            }
        }
    }

    private fun withMainThread(block: () -> Unit) {
        val owner = Thread.currentThread()
        val registration = ThreadGuardHost.registerPlatformBridge(object : ThreadGuardPlatformBridge {
            override fun isMainThread(): Boolean = Thread.currentThread() === owner
        })
        try {
            block()
        } finally {
            registration.close()
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
