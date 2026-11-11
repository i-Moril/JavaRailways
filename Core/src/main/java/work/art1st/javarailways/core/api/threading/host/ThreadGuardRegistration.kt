package work.art1st.javarailways.core.api.threading.host

/**
 * Identity-bound lifecycle token for the process-wide thread-guard bridge.
 *
 * Closing is synchronous and idempotent. A token can remove only the exact bridge registration it
 * represents; it cannot clear a later host registration.
 */
interface ThreadGuardRegistration : AutoCloseable {
    /** Idempotently removes this token's exact bridge registration, if it is still current. */
    override fun close()
}
