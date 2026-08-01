package work.art1st.javarailways.core.api.util.annotation

/** Marks events dispatched synchronously on a physics worker rather than on the platform thread. */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This event is dispatched on an asynchronous physics worker. The listener must be thread-safe."
)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class AsyncEventApi
