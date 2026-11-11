package work.art1st.javarailways.core.api.threading

import work.art1st.javarailways.core.util.AsyncTickThreadMarker
import work.art1st.javarailways.core.util.ThreadGuardBridgeRegistry

/**
 * Fail-fast checks for JavaRailways thread-ownership contracts.
 *
 * This object only classifies the calling thread. It never dispatches, schedules, locks, or moves
 * work to another context. Callers must use the owning runtime's scheduler before invoking an API
 * whose guard would otherwise fail.
 *
 * The platform host registration is process-wide. Its publication is visible to concurrent callers,
 * but registration lifecycle remains a trusted host responsibility.
 */
object ThreadGuard {
    /** Returns whether the caller is in the registered platform owner context. */
    fun isMainThread(): Boolean = ThreadGuardBridgeRegistry.isMainThread()

    /** Returns whether the caller is executing the Core asynchronous physics-tick phase. */
    fun isAsyncTickThread(): Boolean = AsyncTickThreadMarker.isAsyncTickThread()

    /** Returns a diagnostic classification of the calling thread. */
    fun currentContext(): ThreadContext = when {
        isMainThread() -> ThreadContext.MAIN_THREAD
        isAsyncTickThread() -> ThreadContext.ASYNC_TICK_WORKER
        else -> ThreadContext.UNKNOWN_BACKGROUND
    }

    /** Fails with [IllegalStateException] unless the caller is in the main/simulation context. */
    fun ensureMainThread(operation: String) {
        check(isMainThread()) {
            "$operation must run on the main thread, but current context is ${currentContext()}"
        }
    }

    /** Fails with [IllegalStateException] when the caller is in the main/simulation context. */
    fun ensureOffMainThread(operation: String) {
        check(!isMainThread()) {
            "$operation must not run on the main thread"
        }
    }

    /** Fails with [IllegalStateException] unless the caller is in the Core async-tick context. */
    fun ensureAsyncTickThread(operation: String) {
        check(isAsyncTickThread()) {
            "$operation must run on an async tick worker thread, but current context is ${currentContext()}"
        }
    }
}
