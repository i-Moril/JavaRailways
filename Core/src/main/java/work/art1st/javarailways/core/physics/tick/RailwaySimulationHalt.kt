package work.art1st.javarailways.core.physics.tick

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.Logger

/** Process-wide terminal gate. Halting preserves trains, occupancy and claims for diagnosis. */
object RailwaySimulationHalt {
    private val failure = AtomicReference<PhysicsStalledException?>()
    private val coordinators = ConcurrentHashMap.newKeySet<PhysicsCoordinator>()
    private val logger = Logger.getLogger(RailwaySimulationHalt::class.java.name)

    val isHalted: Boolean get() = failure.get() != null

    fun ensureRunning() {
        failure.get()?.let { throw it }
    }

    fun halt(message: String, cause: Throwable? = null): PhysicsStalledException {
        val candidate = PhysicsStalledException(message, cause)
        if (failure.compareAndSet(null, candidate)) {
            logger.log(Level.SEVERE, "All railway simulation halted: $message", candidate)
            coordinators.forEach { it.requestGlobalHalt(candidate) }
        }
        return checkNotNull(failure.get())
    }

    internal fun register(coordinator: PhysicsCoordinator) {
        coordinators.add(coordinator)
        failure.get()?.let(coordinator::requestGlobalHalt)
    }

    internal fun unregister(coordinator: PhysicsCoordinator) {
        coordinators.remove(coordinator)
    }
}
