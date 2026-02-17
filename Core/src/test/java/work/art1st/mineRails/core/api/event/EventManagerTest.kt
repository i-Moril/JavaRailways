package work.art1st.mineRails.core.api.event

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import work.art1st.mineRails.core.api.event.processor.EventListener
import work.art1st.mineRails.core.api.event.processor.EventManager
import work.art1st.mineRails.core.api.event.processor.HandlesEvent
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.jvm.isAccessible

class EventManagerTest {
    interface MockEventInterfaceA : RailEvent
    val bitMockEventInterfaceA = 0x01
    interface MockEventInterfaceB : RailEvent
    val bitMockEventInterfaceB = 0x10
    abstract class MockEventClass : MockEventInterfaceA
    val bitMockEventClass = 0x02
    class MockEventA : MockEventInterfaceA, MockEventClass()
    val bitMockEventA = 0x04
    class MockEventB : MockEventInterfaceB
    val bitMockEventB = 0x20
    class MockEventC : MockEventInterfaceA, MockEventInterfaceB
    val bitMockEventC = 0x40

    inner class MockEventListener : EventListener {
        var activated: Int = 0

        @HandlesEvent
        fun handleInterfaceA(event: MockEventInterfaceA) {
            assertEquals(activated and bitMockEventInterfaceA, 0)
            activated = activated or bitMockEventInterfaceA
        }

        @HandlesEvent
        fun handleInterfaceB1(event: MockEventInterfaceB) {
            assertEquals(activated and bitMockEventInterfaceB, 0)
            activated = activated or bitMockEventInterfaceB
        }

        @HandlesEvent
        fun handleClass(event: MockEventClass) {
            assertEquals(activated and bitMockEventClass, 0)
            activated = activated or bitMockEventClass
        }

        @HandlesEvent
        fun handleA(event: MockEventA) {
            assertEquals(activated and bitMockEventA, 0)
            activated = activated or bitMockEventA
        }

        @HandlesEvent
        fun handleB(event: MockEventB) {
            assertEquals(activated and bitMockEventB, 0)
            activated = activated or bitMockEventB
        }

        @HandlesEvent
        fun handleC(event: MockEventC) {
            assertEquals(activated and bitMockEventC, 0)
            activated = activated or bitMockEventC
        }
    }

    lateinit var mockEventHandler: MockEventListener

    @BeforeEach
    fun setUp() {
        mockEventHandler = MockEventListener()
        EventManager.register(mockEventHandler)
    }

    @Test
    fun `test event inheritance`() {
        fireEvent(MockEventA())
        assertEquals(mockEventHandler.activated, bitMockEventA or bitMockEventClass or bitMockEventInterfaceA)
        fireEvent(MockEventB())
        assertEquals(mockEventHandler.activated, bitMockEventB or bitMockEventInterfaceB)
        fireEvent(MockEventC())
        assertEquals(mockEventHandler.activated, bitMockEventC or bitMockEventInterfaceA or bitMockEventInterfaceB)
    }

    @Test
    fun `test unregister`() {
        EventManager.unregister(mockEventHandler)
        EventManager::class.declaredMemberProperties.forEach { member ->
            if (member.name == "handlers") {
                member.isAccessible = true
                val handlers = member.getter.call()!!
                handlers::class.declaredMemberFunctions.forEach {
                    if (it.name == "getList") {
                        it.isAccessible = true
                        assert((it.call(handlers, 0) as List<*>).isEmpty())
                        assert((it.call(handlers, 1) as List<*>).isEmpty())
                        assert((it.call(handlers, 2) as List<*>).isEmpty())
                        assert((it.call(handlers, 3) as List<*>).isEmpty())
                        assert((it.call(handlers, 4) as List<*>).isEmpty())
                    }
                }
            }
        }
    }

    fun fireEvent(event: RailEvent) {
        mockEventHandler.activated = 0
        EventManager.fire(event)
    }
}