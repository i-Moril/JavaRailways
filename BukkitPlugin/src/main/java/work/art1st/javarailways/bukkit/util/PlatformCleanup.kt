package work.art1st.javarailways.bukkit.util

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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
        val operation = start(timeoutMillis, skipOwnerTasks, action)
        try {
            operation.completion.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (failure: java.util.concurrent.ExecutionException) {
            throw failure.cause ?: failure
        } finally {
            operation.cancel()
        }
    }

    /** Submits cleanup on the caller's owner, then returns without waiting for other regions. */
    fun start(
        timeoutMillis: Long = 10_000L,
        skipOwnerTasks: Boolean = false,
        action: () -> Unit
    ): Operation {
        check(current.get() == null) { "Cannot start cleanup from inside a cleanup task" }
        val batch = Batch(skipOwnerTasks)
        val operation = Operation(batch)
        batch.finished.whenComplete { _, _ -> operation.finish() }
        try {
            withBatch(batch, action)
        } catch (failure: Throwable) {
            batch.failures.add(failure)
        } finally {
            batch.completeOne()
        }
        CompletableFuture.delayedExecutor(timeoutMillis, TimeUnit.MILLISECONDS).execute {
            if (!operation.completion.isDone) {
                operation.cancel(TimeoutException("Timed out waiting for platform cleanup"))
            }
        }
        return operation
    }

    internal class Operation internal constructor(private val batch: Batch) {
        val completion = CompletableFuture<Unit>()

        internal fun finish() {
            batch.active.set(false)
            if (batch.failures.isEmpty()) completion.complete(Unit)
            else if (batch.failures.size == 1 && batch.failures.peek() is TimeoutException) {
                completion.completeExceptionally(batch.failures.peek())
            } else completion.completeExceptionally(IllegalStateException(
                "Platform cleanup failed (${batch.failures.size} errors)"
            ).also { failure -> batch.failures.forEach(failure::addSuppressed) })
        }

        /** Reject queued callbacks and observe callbacks that already entered before teardown. */
        fun cancel(failure: Throwable? = null): CompletableFuture<Unit> {
            synchronized(batch) {
                if (failure != null && !completion.isDone) batch.failures.add(failure)
                batch.active.set(false)
                if (batch.running == 0) batch.drained.complete(Unit)
            }
            if (failure != null) finish()
            return batch.drained
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
        var running = 0
        val drained = CompletableFuture<Unit>()

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
            runIfActive(action)
        }

        /** Entity retirement is successful cleanup: the native entity no longer exists. */
        fun retired(action: () -> Unit = {}) = complete {
            runIfActive(action)
        }

        private fun runIfActive(action: () -> Unit) {
            synchronized(batch) {
                if (!batch.active.get()) return
                batch.running++
            }
            try {
                withBatch(batch, action)
            } finally {
                synchronized(batch) {
                    batch.running--
                    if (!batch.active.get() && batch.running == 0) batch.drained.complete(Unit)
                }
            }
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
