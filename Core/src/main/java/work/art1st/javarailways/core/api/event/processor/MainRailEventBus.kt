package work.art1st.javarailways.core.api.event.processor

import work.art1st.javarailways.core.api.event.MainThreadRailEvent
import work.art1st.javarailways.core.api.threading.ThreadGuard

/**
 * Process-wide synchronous bus for events owned by the platform main thread.
 *
 * [publish] never schedules work. It verifies the current thread, calls matching handlers in
 * priority order, and isolates each handler failure. Listener registration is process-wide rather
 * than world-scoped; owners must retain and close the returned [EventRegistration].
 */
object MainRailEventBus {
    private val dispatcher = TypedRailEventBus(MainThreadRailEvent::class, "main-thread")

    fun register(listener: EventListener): EventRegistration = dispatcher.register(listener)

    fun unregister(listener: EventListener) = dispatcher.unregister(listener)

    fun publish(event: MainThreadRailEvent) {
        ThreadGuard.ensureMainThread("MainRailEventBus.publish(${event::class.simpleName})")
        dispatcher.publish(event)
    }

    /** Structural decisions must not commit after an isolated listener failure. */
    internal fun publishAndReportFailures(event: MainThreadRailEvent): Boolean {
        ThreadGuard.ensureMainThread("MainRailEventBus.publish(${event::class.simpleName})")
        return dispatcher.publishAndReportFailures(event)
    }
}
