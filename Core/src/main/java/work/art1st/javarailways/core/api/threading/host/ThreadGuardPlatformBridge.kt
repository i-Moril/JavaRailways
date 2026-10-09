package work.art1st.javarailways.core.api.threading.host

/**
 * Trusted-host adapter used by JavaRailways to recognize the simulation owner thread.
 *
 * Only the platform lifecycle owner registers this bridge through [ThreadGuardHost]. Implementations
 * must be thread-safe, non-blocking, and must not perform scheduling. The result is queried from
 * arbitrary threads and therefore must reflect current platform ownership without caller-side
 * synchronization.
 */
interface ThreadGuardPlatformBridge {
    /** Returns whether the calling thread currently owns main/simulation-thread state. */
    fun isMainThread(): Boolean
}
