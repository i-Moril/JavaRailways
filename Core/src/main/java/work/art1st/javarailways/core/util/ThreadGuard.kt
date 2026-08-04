package work.art1st.javarailways.core.util

enum class ThreadContext {
    MAIN_THREAD,
    ASYNC_TICK_WORKER,
    UNKNOWN_BACKGROUND
}

object IsolatedMainThreadMarker {
    private val depth = ThreadLocal.withInitial { 0 }

    fun isActive(): Boolean = depth.get() > 0

    fun <T> run(action: () -> T): T {
        depth.set(depth.get() + 1)
        return try { action() } finally {
            val next = depth.get() - 1
            if (next == 0) depth.remove() else depth.set(next)
        }
    }
}

object ThreadGuard {
    @Volatile
    private var platformBridge: ThreadGuardPlatformBridge? = null

    fun installPlatformBridge(bridge: ThreadGuardPlatformBridge) {
        platformBridge = bridge
    }

    fun clearPlatformBridge() {
        platformBridge = null
    }

    fun isMainThread(): Boolean =
        IsolatedMainThreadMarker.isActive() || platformBridge?.isMainThread() == true

    fun isAsyncTickThread(): Boolean = AsyncTickThreadMarker.isAsyncTickThread()

    fun currentContext(): ThreadContext = when {
        isMainThread() -> ThreadContext.MAIN_THREAD
        isAsyncTickThread() -> ThreadContext.ASYNC_TICK_WORKER
        else -> ThreadContext.UNKNOWN_BACKGROUND
    }

    fun ensureMainThread(operation: String) {
        check(isMainThread()) {
            "$operation must run on the main thread, but current context is ${currentContext()}"
        }
    }

    fun ensureOffMainThread(operation: String) {
        check(!isMainThread()) {
            "$operation must not run on the main thread"
        }
    }

    fun ensureAsyncTickThread(operation: String) {
        check(isAsyncTickThread()) {
            "$operation must run on an async tick worker thread, but current context is ${currentContext()}"
        }
    }
}
