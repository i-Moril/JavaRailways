package work.art1st.javarailways.core.api.event.processor

/**
 * Scoped registration returned by a JavaRailways event bus.
 *
 * Registrations are idempotently closeable and must be closed when their owning plugin or service
 * stops. Closing prevents later dispatch snapshots from selecting the handler; an invocation already
 * in progress on another thread may still complete.
 */
interface EventRegistration : AutoCloseable {
    val isClosed: Boolean

    override fun close()
}
