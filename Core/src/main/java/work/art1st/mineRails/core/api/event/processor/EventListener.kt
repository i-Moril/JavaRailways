package work.art1st.mineRails.core.api.event.processor

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class HandlesEvent(val priority: Priority = Priority.MEDIUM) {}

enum class Priority(val value: Int) {
    HIGHEST(0),
    HIGH(1),
    MEDIUM(2),
    LOW(3),
    LOWEST(4),
}

interface EventListener {}