package work.art1st.mineRails.bukkit.util

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import io.papermc.paper.threadedregions.scheduler.ScheduledTask

/**
 * Paper/Folia-compatible execution boundary. Paper implements these schedulers too, so callers do
 * not need a reflection-based Folia branch.
 */
interface PlatformDispatcher {
    fun runGlobal(action: () -> Unit)
    fun runGlobalAtFixedRate(initialDelayTicks: Long, periodTicks: Long, action: () -> Unit): ScheduledTask
    fun runAsync(action: () -> Unit)
    fun runAt(location: Location, action: () -> Unit)
    fun runForEntity(entity: Entity, action: () -> Unit, retired: () -> Unit = {}): Boolean
    fun runForEntityLater(
        entity: Entity,
        delayTicks: Long,
        action: () -> Unit,
        retired: () -> Unit = {}
    ): ScheduledTask?

    fun owns(location: Location): Boolean
    fun owns(entity: Entity): Boolean
}

class PaperPlatformDispatcher(
    private val plugin: Plugin
) : PlatformDispatcher {
    override fun runGlobal(action: () -> Unit) {
        Bukkit.getGlobalRegionScheduler().execute(plugin, Runnable(action))
    }

    override fun runGlobalAtFixedRate(
        initialDelayTicks: Long,
        periodTicks: Long,
        action: () -> Unit
    ): ScheduledTask {
        require(initialDelayTicks > 0L) { "global scheduler initial delay must be positive" }
        require(periodTicks > 0L) { "global scheduler period must be positive" }
        return Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { action() }, initialDelayTicks, periodTicks)
    }

    override fun runAsync(action: () -> Unit) {
        Bukkit.getAsyncScheduler().runNow(plugin) { action() }
    }

    override fun runAt(location: Location, action: () -> Unit) {
        Bukkit.getRegionScheduler().execute(plugin, location, Runnable(action))
    }

    override fun runForEntity(entity: Entity, action: () -> Unit, retired: () -> Unit): Boolean =
        entity.scheduler.execute(plugin, Runnable(action), Runnable(retired), 0L)

    override fun runForEntityLater(
        entity: Entity,
        delayTicks: Long,
        action: () -> Unit,
        retired: () -> Unit
    ): ScheduledTask? {
        require(delayTicks > 0L) { "entity scheduler delay must be positive" }
        return entity.scheduler.runDelayed(plugin, { action() }, Runnable(retired), delayTicks)
    }

    override fun owns(location: Location): Boolean = Bukkit.isOwnedByCurrentRegion(location)

    override fun owns(entity: Entity): Boolean = Bukkit.isOwnedByCurrentRegion(entity)
}
