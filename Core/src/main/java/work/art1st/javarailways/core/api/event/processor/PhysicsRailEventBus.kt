package work.art1st.javarailways.core.api.event.processor

import work.art1st.javarailways.core.api.event.PhysicsRailEvent
import work.art1st.javarailways.core.api.util.annotation.AsyncEventApi
import work.art1st.javarailways.core.api.threading.ThreadGuard

/**
 * Process-wide synchronous bus for events emitted by JavaRailways physics coordinators and workers.
 *
 * Despite running off the platform thread, [publish] is not an asynchronous scheduler: handlers run
 * immediately on the publishing coordinator or worker. A listener can therefore be called concurrently
 * across worlds or workers and must not block or access platform APIs.
 */
object PhysicsRailEventBus {
    private val dispatcher = TypedRailEventBus(PhysicsRailEvent::class, "physics")

    fun register(listener: EventListener): EventRegistration = dispatcher.register(listener)

    fun unregister(listener: EventListener) = dispatcher.unregister(listener)

    @AsyncEventApi
    fun publish(event: PhysicsRailEvent) {
        ThreadGuard.ensureAsyncTickThread("PhysicsRailEventBus.publish(${event::class.simpleName})")
        dispatcher.publish(event)
    }
}
