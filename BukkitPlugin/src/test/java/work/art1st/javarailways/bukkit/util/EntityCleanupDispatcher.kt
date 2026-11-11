package work.art1st.javarailways.bukkit.util

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Location
import org.bukkit.entity.Entity

/** Controlled entity scheduler for cleanup boundary tests; no Bukkit runtime is started. */
internal class EntityCleanupDispatcher : PlatformDispatcher {
    var action: (() -> Unit)? = null
    var retirement: (() -> Unit)? = null
    var accept = true
    var submissionFailure: RuntimeException? = null
    var immediate = false

    override fun runForEntity(entity: Entity, action: () -> Unit, retired: () -> Unit): Boolean {
        submissionFailure?.let { throw it }
        if (!accept) return false
        this.action = action
        retirement = retired
        if (immediate) action()
        return true
    }
    override fun runGlobal(action: () -> Unit) = error("Unexpected global dispatch")
    override fun runGlobalAtFixedRate(initialDelayTicks: Long, periodTicks: Long, action: () -> Unit): ScheduledTask = error("Unexpected timer")
    override fun runAsync(action: () -> Unit) = error("Unexpected async dispatch")
    override fun runAt(location: Location, action: () -> Unit) = error("Unexpected location dispatch")
    override fun runForEntityLater(entity: Entity, delayTicks: Long, action: () -> Unit, retired: () -> Unit): ScheduledTask? = error("Unexpected timer")
    override fun runForEntityAtFixedRate(entity: Entity, initialDelayTicks: Long, periodTicks: Long, action: (ScheduledTask) -> Unit, retired: () -> Unit): ScheduledTask? = error("Unexpected timer")
    override fun owns(location: Location) = false
    override fun owns(entity: Entity) = false
    override fun metrics() = PlatformDispatchMetrics(0, 0, 0, 0, 0, 0, 0, 0)
}
