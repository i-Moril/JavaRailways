package work.art1st.mineRails.core.api.event.processor

/** Marks a member function as an [EventManager] handler. */
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
 * Marker for an object registered with [EventManager.register].
 *
 * Handler functions must have exactly one event parameter and be annotated with [HandlesEvent].
 * Registration must be paired with [EventManager.unregister] when the owning plugin is disabled.
 */
interface EventListener
