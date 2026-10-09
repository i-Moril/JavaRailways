package work.art1st.javarailways.core.util

/**
 * First-party implementation seam for headless simulations that exclusively own their Core world.
 *
 * This is not part of `core.api`, is not a third-party ownership capability, and must never wrap
 * live server callbacks or Bukkit access. It executes synchronously on the caller, supports nested
 * lexical scopes, propagates failures, and always restores the previous marker depth.
 */
object IsolatedSimulationThreadMarker {
    private val depth = ThreadLocal.withInitial { 0 }

    fun isActive(): Boolean = depth.get() > 0

    fun <T> run(action: () -> T): T {
        depth.set(depth.get() + 1)
        return try {
            action()
        } finally {
            val next = depth.get() - 1
            if (next == 0) depth.remove() else depth.set(next)
        }
    }
}
