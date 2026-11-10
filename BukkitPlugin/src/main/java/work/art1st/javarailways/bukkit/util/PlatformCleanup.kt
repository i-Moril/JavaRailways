package work.art1st.javarailways.bukkit.util

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Tracks owner tasks (including tasks submitted by another cleanup task) before plugin disable. */
internal object PlatformCleanup {
    private val current = ThreadLocal<Batch?>()

    fun runAndAwait(
        timeoutMillis: Long = 10_000L,
        skipOwnerTasks: Boolean = false,
        action: () -> Unit
    ) {
        check(current.get() == null) { "Cannot wait for cleanup from inside a cleanup task" }
        val batch = Batch(skipOwnerTasks)
        try {
            withBatch(batch, action)
        } catch (failure: Throwable) {
            batch.failures.add(failure)
        } finally {
            batch.completeOne()
        }
        try {
            batch.finished.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } finally {
            // Timed-out callbacks must not execute against a subsequent enable of the same plugin.
            batch.active.set(false)
        }
        if (batch.failures.isNotEmpty()) {
            throw IllegalStateException("Platform cleanup failed (${batch.failures.size} errors)").also {
                batch.failures.forEach(it::addSuppressed)
            }
        }
    }

    fun capture(action: () -> Unit): Task? = current.get()?.let { Task(it, action) }

    /** Folia full shutdown has stopped region ticks; only local teardown remains executable. */
    fun skipsOwnerTasks(): Boolean = current.get()?.skipOwnerTasks == true

    private fun withBatch(batch: Batch, action: () -> Unit) {
        val previous = current.get()
        current.set(batch)
        try {
            action()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }

    internal class Batch(val skipOwnerTasks: Boolean = false) {
        val active = AtomicBoolean(true)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val finished = CompletableFuture<Unit>()
        val pending = AtomicInteger(1)

        fun completeOne() {
            if (pending.decrementAndGet() == 0) finished.complete(Unit)
        }
    }

    internal class Task(private val batch: Batch, private val action: () -> Unit) {
        private val completed = AtomicBoolean()

        init {
            batch.pending.incrementAndGet()
        }

        fun run() = complete {
            if (batch.active.get()) withBatch(batch, action)
        }

        /** Entity retirement is successful cleanup: the native entity no longer exists. */
        fun retired(action: () -> Unit = {}) = complete {
            if (batch.active.get()) withBatch(batch, action)
        }

        fun failed(failure: Throwable) = complete { throw failure }

        private fun complete(action: () -> Unit) {
            if (!completed.compareAndSet(false, true)) return
            try {
                action()
            } catch (failure: Throwable) {
                batch.failures.add(failure)
            } finally {
                batch.completeOne()
            }
        }
    }
}
