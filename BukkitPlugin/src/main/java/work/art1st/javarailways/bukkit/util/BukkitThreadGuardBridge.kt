package work.art1st.javarailways.bukkit.util

import org.bukkit.Bukkit
import work.art1st.javarailways.core.api.threading.host.ThreadGuardPlatformBridge
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Bridges Core simulation-thread contracts to the single JavaRailways simulation owner. */
object BukkitThreadGuardBridge : ThreadGuardPlatformBridge {
    private val simulationDepth = ThreadLocal.withInitial { 0 }
    private val simulationLock = ReentrantLock()

    override fun isMainThread(): Boolean = simulationDepth.get() > 0 || Bukkit.isPrimaryThread()

    fun <T> runInSimulationContext(action: () -> T): T = simulationLock.withLock {
        simulationDepth.set(simulationDepth.get() + 1)
        try {
            action()
        } finally {
            val remaining = simulationDepth.get() - 1
            if (remaining == 0) simulationDepth.remove() else simulationDepth.set(remaining)
        }
    }

    /** Releases the simulation lock while waiting for a region task, then restores this context. */
    fun <T> runOutsideSimulationContext(action: () -> T): T {
        val holdCount = simulationLock.holdCount
        if (holdCount == 0) return action()
        val depth = simulationDepth.get()
        simulationDepth.remove()
        repeat(holdCount) { simulationLock.unlock() }
        try {
            return action()
        } finally {
            repeat(holdCount) { simulationLock.lock() }
            simulationDepth.set(depth)
        }
    }
}
