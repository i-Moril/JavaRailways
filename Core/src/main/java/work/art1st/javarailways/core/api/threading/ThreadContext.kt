package work.art1st.javarailways.core.api.threading

/**
 * The execution context recognized by [ThreadGuard].
 *
 * This is a diagnostic value, not a scheduler or a stable thread identity. A caller must still
 * use the owner module's scheduler to enter the context required by an operation.
 */
enum class ThreadContext {
    MAIN_THREAD,
    ASYNC_TICK_WORKER,
    UNKNOWN_BACKGROUND
}
