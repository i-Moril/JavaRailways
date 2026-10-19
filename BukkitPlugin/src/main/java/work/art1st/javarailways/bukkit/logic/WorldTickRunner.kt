package work.art1st.javarailways.bukkit.logic

import java.util.concurrent.Executors

internal data class WorldTickWake(val index: Long, val millis: Long)

/** Crossing a periodic boundary is sufficient even when intermediate host wakeups were coalesced. */
internal class WorldSnapshotInterval(private val periodTicks: Long) {
    private var lastBucket = 0L

    fun captureIfDue(tickIndex: Long, capture: () -> Unit) {
        val bucket = tickIndex / periodTicks
        if (bucket <= lastBucket) return
        capture()
        lastBucket = bucket
    }
}

/** One execution and one latest pending wake per World; waits never occupy the global scheduler. */
internal class WorldTickRunner(
    name: String,
    private val tick: (WorldTickWake) -> Unit,
    private val failed: (Throwable) -> Unit
) : AutoCloseable {
    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "JavaRailways-world-$name").apply { isDaemon = true }
    }
    private var running = false
    private var pending: WorldTickWake? = null
    private var closed = false

    fun offer(wake: WorldTickWake) = synchronized(lock) {
        if (closed) return@synchronized
        pending = wake
        if (!running) {
            running = true
            executor.execute(::drain)
        }
    }

    private fun drain() {
        while (true) {
            val wake = synchronized(lock) {
                val next = pending
                pending = null
                if (next == null || closed) {
                    running = false
                    null
                } else next
            } ?: return
            try {
                tick(wake)
            } catch (failure: Throwable) {
                failed(failure)
            }
        }
    }

    override fun close() = synchronized(lock) {
        closed = true
        pending = null
        executor.shutdown()
    }
}

/** Directory operations are short; an old global offer cannot recreate a retired World's runner. */
internal class WorldTickRunnerRegistry<K : Any> : AutoCloseable {
    private val lock = Any()
    private val runners = HashMap<K, WorldTickRunner>()
    private val retired = java.util.Collections.newSetFromMap(java.util.WeakHashMap<K, Boolean>())
    private var stopped = false

    fun offer(key: K, wake: WorldTickWake, create: () -> WorldTickRunner) = synchronized(lock) {
        if (!stopped && key !in retired) runners.getOrPut(key, create).offer(wake)
    }

    fun retire(key: K) = synchronized(lock) {
        retired.add(key)
        runners.remove(key)?.close()
        Unit
    }

    override fun close() = synchronized(lock) {
        stopped = true
        runners.values.forEach(WorldTickRunner::close)
        runners.clear()
    }
}
