package work.art1st.javarailways.core.physics

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Serializes ordinary domain work for one World; it grants no platform thread permission. */
class WorldExecutionOwner {
    private val order = nextOrder.incrementAndGet()
    private val lock = ReentrantLock()

    internal fun isQueued(thread: Thread): Boolean = lock.hasQueuedThread(thread)

    fun <T> run(action: () -> T): T = lock.withLock {
        val owners = heldOwners.get()
        val added = this !in owners
        if (added) owners.add(this)
        try {
            action()
        } finally {
            if (added) owners.remove(this)
            if (owners.isEmpty()) heldOwners.remove()
        }
    }

    companion object {
        private val nextOrder = AtomicLong()
        private val heldOwners = ThreadLocal.withInitial { mutableListOf<WorldExecutionOwner>() }

        fun ensureNoOwner(operation: String) {
            check(heldOwners.get().isEmpty()) { "$operation must enter before a World owner" }
        }

        /** Low-frequency management transactions acquire the captured worlds in a stable order. */
        fun <T> runTogether(owners: Collection<WorldExecutionOwner>, action: () -> T): T {
            val ordered = owners.distinct().sortedBy { it.order }
            check(heldOwners.get().isEmpty() || ordered.all { it.lock.isHeldByCurrentThread }) {
                "Cross-world management must enter before a single World owner"
            }
            fun enter(index: Int): T = if (index == ordered.size) action()
                else ordered[index].run { enter(index + 1) }
            return enter(0)
        }

        /** A synchronous platform wait must not retain any domain owner needed by its callback. */
        fun <T> runWithoutOwners(action: () -> T): T {
            val owners = heldOwners.get()
            val captured = owners.map { it to it.lock.holdCount }
            if (captured.isEmpty()) return action()
            heldOwners.remove()
            captured.asReversed().forEach { (owner, count) -> repeat(count) { owner.lock.unlock() } }
            try {
                return action()
            } finally {
                captured.forEach { (owner, count) -> repeat(count) { owner.lock.lock() } }
                heldOwners.set(owners)
            }
        }
    }
}
