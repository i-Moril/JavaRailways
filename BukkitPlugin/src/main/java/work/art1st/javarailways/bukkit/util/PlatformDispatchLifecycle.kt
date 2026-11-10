package work.art1st.javarailways.bukkit.util

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** One enable generation. An old callback stays retired even after its plugin is enabled again. */
internal class PlatformDispatchLifecycle {
    private val lock = ReentrantLock()
    private val idle = lock.newCondition()
    private val localDepth = ThreadLocal.withInitial { 0 }
    private var active = true
    private var running = 0

    fun isActive(): Boolean = lock.withLock { active }

    fun execute(action: () -> Unit): Boolean {
        lock.withLock {
            if (!active) return false
            running++
        }
        localDepth.set(localDepth.get() + 1)
        try {
            action()
        } finally {
            val depth = localDepth.get() - 1
            if (depth == 0) localDepth.remove() else localDepth.set(depth)
            lock.withLock {
                running--
                idle.signalAll()
            }
        }
        return true
    }

    fun stopAndAwait(timeoutMillis: Long = 10_000L) {
        var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        lock.withLock {
            active = false
            // Disable may be initiated by the current callback; never wait for ourselves.
            val currentActions = localDepth.get()
            while (running > currentActions) {
                check(remaining > 0L) { "Timed out waiting for platform actions to stop ($running running)" }
                remaining = idle.awaitNanos(remaining)
            }
        }
    }
}
