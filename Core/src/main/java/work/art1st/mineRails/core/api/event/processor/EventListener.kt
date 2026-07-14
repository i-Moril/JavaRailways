package work.art1st.mineRails.core.api.event.processor

/** Marks a member function as a typed MineRails event-bus handler. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class HandlesEvent(
    /** Handler order, from [Priority.HIGHEST] to [Priority.LOWEST]. */
    val priority: Priority = Priority.MEDIUM
)

/** Event handler order. This event bus does not currently support cancellation. */
enum class Priority(val value: Int) {
    HIGHEST(0),
    HIGH(1),
    MEDIUM(2),
    LOW(3),
    LOWEST(4),
}

/**
 * Marker for an object registered with [MainRailEventBus] or [PhysicsRailEventBus].
 *
 * Handler functions must have exactly one event parameter from the selected bus and be annotated
 * with [HandlesEvent]. Retain and close the returned [EventRegistration] when the owner stops.
 */
interface EventListener
