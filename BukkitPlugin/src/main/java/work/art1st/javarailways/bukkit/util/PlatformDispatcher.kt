package work.art1st.javarailways.bukkit.util

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import java.util.concurrent.atomic.AtomicLong

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
    fun runForEntityAtFixedRate(
        entity: Entity,
        initialDelayTicks: Long,
        periodTicks: Long,
        action: (ScheduledTask) -> Unit,
        retired: () -> Unit = {}
    ): ScheduledTask?

    fun owns(location: Location): Boolean
    fun owns(entity: Entity): Boolean
    fun metrics(): PlatformDispatchMetrics
}

data class PlatformDispatchMetrics(
    val globalExecutions: Long,
    val asyncExecutions: Long,
    val regionSubmissions: Long,
    val regionExecutions: Long,
    val entitySubmissions: Long,
    val entityExecutions: Long,
    val directExecutions: Long,
    val retiredTasks: Long
)

class PaperPlatformDispatcher(
    private val plugin: Plugin
) : PlatformDispatcher {
    private val globalExecutions = AtomicLong()
    private val asyncExecutions = AtomicLong()
    private val regionSubmissions = AtomicLong()
    private val regionExecutions = AtomicLong()
    private val entitySubmissions = AtomicLong()
    private val entityExecutions = AtomicLong()
    private val directExecutions = AtomicLong()
    private val retiredTasks = AtomicLong()

    override fun runGlobal(action: () -> Unit) {
        Bukkit.getGlobalRegionScheduler().execute(plugin, Runnable {
            globalExecutions.incrementAndGet()
            action()
        })
    }

    override fun runGlobalAtFixedRate(
        initialDelayTicks: Long,
        periodTicks: Long,
        action: () -> Unit
    ): ScheduledTask {
        require(initialDelayTicks > 0L) { "global scheduler initial delay must be positive" }
        require(periodTicks > 0L) { "global scheduler period must be positive" }
        return Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, {
            globalExecutions.incrementAndGet()
            action()
        }, initialDelayTicks, periodTicks)
    }

    override fun runAsync(action: () -> Unit) {
        Bukkit.getAsyncScheduler().runNow(plugin) {
            asyncExecutions.incrementAndGet()
            action()
        }
    }

    override fun runAt(location: Location, action: () -> Unit) {
        if (owns(location)) {
            directExecutions.incrementAndGet()
            action()
            return
        }
        regionSubmissions.incrementAndGet()
        Bukkit.getRegionScheduler().execute(plugin, location, Runnable {
            regionExecutions.incrementAndGet()
            action()
        })
    }

    override fun runForEntity(entity: Entity, action: () -> Unit, retired: () -> Unit): Boolean {
        if (owns(entity)) {
            directExecutions.incrementAndGet()
            action()
            return true
        }
        entitySubmissions.incrementAndGet()
        return entity.scheduler.execute(plugin, Runnable {
            entityExecutions.incrementAndGet()
            action()
        }, Runnable {
            retiredTasks.incrementAndGet()
            retired()
        }, 0L)
    }

    override fun runForEntityLater(
        entity: Entity,
        delayTicks: Long,
        action: () -> Unit,
        retired: () -> Unit
    ): ScheduledTask? {
        require(delayTicks > 0L) { "entity scheduler delay must be positive" }
        entitySubmissions.incrementAndGet()
        return entity.scheduler.runDelayed(plugin, {
            entityExecutions.incrementAndGet()
            action()
        }, Runnable {
            retiredTasks.incrementAndGet()
            retired()
        }, delayTicks)
    }

    override fun runForEntityAtFixedRate(
        entity: Entity,
        initialDelayTicks: Long,
        periodTicks: Long,
        action: (ScheduledTask) -> Unit,
        retired: () -> Unit
    ): ScheduledTask? {
        require(initialDelayTicks > 0L) { "entity scheduler initial delay must be positive" }
        require(periodTicks > 0L) { "entity scheduler period must be positive" }
        entitySubmissions.incrementAndGet()
        return entity.scheduler.runAtFixedRate(plugin, { task ->
            entityExecutions.incrementAndGet()
            action(task)
        }, Runnable {
            retiredTasks.incrementAndGet()
            retired()
        }, initialDelayTicks, periodTicks)
    }

    override fun owns(location: Location): Boolean = Bukkit.isOwnedByCurrentRegion(location)

    override fun owns(entity: Entity): Boolean = Bukkit.isOwnedByCurrentRegion(entity)

    override fun metrics(): PlatformDispatchMetrics = PlatformDispatchMetrics(
        globalExecutions = globalExecutions.get(),
        asyncExecutions = asyncExecutions.get(),
        regionSubmissions = regionSubmissions.get(),
        regionExecutions = regionExecutions.get(),
        entitySubmissions = entitySubmissions.get(),
        entityExecutions = entityExecutions.get(),
        directExecutions = directExecutions.get(),
        retiredTasks = retiredTasks.get()
    )
}
