package work.art1st.javarailways.bukkit.util

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounds platform scheduler pressure to one in-flight task plus one latest pending value per key.
 *
 * The caller supplies the target-aware scheduler. An accepted task must invoke [complete] exactly
 * once after the target-owned action finishes or is retired. A synchronous scheduler rejection is
 * cleaned up by this dispatcher before the exception is propagated.
 */
internal class LatestAffinityTaskDispatcher<K : Any, V : Any>(
    private val scheduler: (value: V, complete: () -> Unit) -> Unit
) {
    private val states = ConcurrentHashMap<K, State<V>>()
    private val coalescedValues = AtomicLong(0L)
    private val scheduledTasks = AtomicLong(0L)

    fun offer(key: K, value: V) {
        var dispatchNow: V? = null
        states.compute(key) { _, current ->
            val state = current ?: State()
            if (state.scheduled) {
                if (state.pending != null) coalescedValues.incrementAndGet()
                state.pending = value
            } else {
                state.scheduled = true
                dispatchNow = value
            }
            state
        }
        dispatchNow?.let { dispatch(key, it) }
    }

    fun clear() {
        states.clear()
    }

    fun metrics(): Metrics = Metrics(
        activeKeys = states.size,
        coalescedValues = coalescedValues.get(),
        scheduledTasks = scheduledTasks.get()
    )

    private fun dispatch(key: K, value: V) {
        scheduledTasks.incrementAndGet()
        try {
            scheduler.invoke(value) { complete(key) }
        } catch (failure: Throwable) {
            complete(key)
            throw failure
        }
    }

    private fun complete(key: K) {
        var dispatchNext: V? = null
        states.computeIfPresent(key) { _, state ->
            val next = state.pending
            if (next == null) {
                state.scheduled = false
                null
            } else {
                state.pending = null
                dispatchNext = next
                state
            }
        }
        dispatchNext?.let { dispatch(key, it) }
    }

    data class Metrics(
        val activeKeys: Int,
        val coalescedValues: Long,
        val scheduledTasks: Long
    )

    private class State<V : Any> {
        var scheduled: Boolean = false
        var pending: V? = null
    }
}
