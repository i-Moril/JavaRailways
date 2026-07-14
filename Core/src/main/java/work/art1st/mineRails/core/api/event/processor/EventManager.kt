package work.art1st.mineRails.core.api.event.processor

import work.art1st.mineRails.core.api.event.MainThreadRailEvent
import work.art1st.mineRails.core.api.event.PhysicsRailEvent
import work.art1st.mineRails.core.api.util.annotation.AsyncEventApi

/**
 * Compatibility facade for the original global event manager.
 *
 * New code should register with exactly one typed bus and retain the returned [EventRegistration].
 * This facade registers compatible handler methods with both buses and therefore cannot provide a
 * scoped registration token.
 */
@Deprecated("Use MainRailEventBus or PhysicsRailEventBus")
object EventManager {
    fun register(eventListener: EventListener) {
        MainRailEventBus.register(eventListener)
        PhysicsRailEventBus.register(eventListener)
    }

    fun unregister(eventListener: EventListener) {
        MainRailEventBus.unregister(eventListener)
        PhysicsRailEventBus.unregister(eventListener)
    }

    fun fire(event: MainThreadRailEvent) {
        MainRailEventBus.publish(event)
    }

    @AsyncEventApi
    fun fireAsync(event: PhysicsRailEvent) {
        PhysicsRailEventBus.publish(event)
    }
}
