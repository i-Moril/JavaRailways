package work.art1st.javarailways.core.api.event.processor

/**
 * Scoped registration returned by a JavaRailways event bus.
 *
 * Registrations are idempotently closeable and must be closed when their owning plugin or service
 * stops. Closing prevents later dispatch snapshots from selecting the handler; callbacks selected
 * by an earlier snapshot may still execute. Closing does not wait for those callbacks.
 */
interface EventRegistration : AutoCloseable {
    /** Whether close has been requested; this is not a callback-completion barrier. */
    val isClosed: Boolean

    override fun close()
}
