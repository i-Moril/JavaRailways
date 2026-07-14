package work.art1st.mineRails.core.api.event

/**
 * Marker for events dispatched by a typed MineRails event bus.
 *
 * MineRails events are not Bukkit events. Dispatch is synchronous on the publishing thread; the
 * event subtype determines whether that thread must be the platform main thread or a physics worker.
 */
interface Event

/** Event that may only be published synchronously on the platform main thread. */
interface MainThreadRailEvent : Event

/** Event that may only be published synchronously on a MineRails physics worker. */
interface PhysicsRailEvent : Event
