package work.art1st.javarailways.tunnelbridgebuilder.rail

/** A lease fences callbacks and mutations against service replacement, including disable/re-enable. */
internal class ServiceGeneration<T : Any> {
    private val lock = Any()
    private var current: Lease? = null

    fun bind(value: T, isAvailable: () -> Boolean): Lease = synchronized(lock) {
        current?.retire()
        Lease(value, isAvailable).also { current = it }
    }

    fun invalidate() = synchronized(lock) {
        current?.retire()
        current = null
    }

    inner class Lease internal constructor(value: T, isAvailable: () -> Boolean) {
        private var value: T? = value
        private var isAvailable: () -> Boolean = isAvailable

        internal fun retire() {
            value = null
            isAvailable = { false }
        }
        fun isCurrent(): Boolean = synchronized(lock) { current === this && isAvailable() }

        // Hold the fence while applying one region group: invalidation waits for that group,
        // then prevents all later groups and undo records from entering the retired runtime.
        fun <R> use(action: (T) -> R): R = synchronized(lock) {
            check(current === this && isAvailable()) { "JavaRailways services are no longer available" }
            action(checkNotNull(value))
        }
    }
}
