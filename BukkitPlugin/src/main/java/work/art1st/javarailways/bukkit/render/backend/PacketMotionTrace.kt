package work.art1st.javarailways.bukkit.render.backend

import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/** Bounded packet-order trace used while diagnosing Folia entity tracking handoffs. */
internal class PacketMotionTrace(private val capacity: Int = DEFAULT_CAPACITY) {
    private val sequence = AtomicLong()
    private val entries = ConcurrentLinkedDeque<String>()

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    fun record(message: String) {
        val entry = "#${sequence.incrementAndGet()} $message"
        entries.addLast(entry)
        while (entries.size > capacity) entries.pollFirst()
    }

    fun snapshot(): List<String> = entries.toList()

    fun clear() {
        entries.clear()
    }

    private companion object {
        const val DEFAULT_CAPACITY = 80
    }
}
