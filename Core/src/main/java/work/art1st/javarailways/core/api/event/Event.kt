package work.art1st.javarailways.core.api.event

/**
 * Marker for events dispatched by a typed JavaRailways event bus.
 *
 * JavaRailways events are not Bukkit events. Dispatch is synchronous on the publishing thread; the
 * event subtype determines whether that thread must be the platform main thread or a physics worker.
 */
interface Event

/** Event that may only be published synchronously on the platform main thread. */
interface MainThreadRailEvent : Event

/** Event that may only be published synchronously on a JavaRailways physics worker. */
interface PhysicsRailEvent : Event
