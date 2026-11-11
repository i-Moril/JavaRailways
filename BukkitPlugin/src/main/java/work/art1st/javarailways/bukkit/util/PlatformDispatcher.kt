package work.art1st.javarailways.bukkit.util

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.RejectedExecutionException

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
    /** Completes only when the entity-owner action has run; retirement/rejection is an explicit failure. */
    fun runForEntityCompletion(entity: Entity, action: () -> Unit): CompletionStage<Void> {
        val completion = CompletableFuture<Void>()
        try {
            val accepted = runForEntity(entity, action = {
                try {
                    action()
                    completion.complete(null)
                } catch (failure: Throwable) {
                    completion.completeExceptionally(failure)
                }
            }, retired = {
                completion.completeExceptionally(RejectedExecutionException("Entity retired before cleanup ran"))
            })
            if (!accepted) {
                completion.completeExceptionally(RejectedExecutionException("Entity cleanup was not accepted"))
            }
        } catch (failure: Throwable) {
            completion.completeExceptionally(failure)
        }
        return completion
    }

    /** Ejection failure must not prevent the removal attempt. */
    fun removeEntityWithPassengers(entity: Entity): CompletionStage<Void> = runForEntityCompletion(entity) {
        var failure: Throwable? = null
        try { entity.eject() } catch (caught: Throwable) { failure = caught }
        try { entity.remove() } catch (caught: Throwable) {
            if (failure == null) failure = caught else if (failure !== caught) failure.addSuppressed(caught)
        }
        failure?.let { throw it }
    }

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
    private val lifecycle = PlatformDispatchLifecycle()

    /** Called outside the simulation lock while the plugin is still enabled. */
    fun stopAndAwait() = lifecycle.stopAndAwait()

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
            lifecycle.execute {
                globalExecutions.incrementAndGet()
                action()
            }
        })
    }

    override fun runGlobalAtFixedRate(
        initialDelayTicks: Long,
        periodTicks: Long,
        action: () -> Unit
    ): ScheduledTask {
        require(initialDelayTicks > 0L) { "global scheduler initial delay must be positive" }
        require(periodTicks > 0L) { "global scheduler period must be positive" }
        return Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { task ->
            if (!lifecycle.execute {
                    globalExecutions.incrementAndGet()
                    action()
                }) task.cancel()
        }, initialDelayTicks, periodTicks)
    }

    override fun runAsync(action: () -> Unit) {
        Bukkit.getAsyncScheduler().runNow(plugin) {
            lifecycle.execute {
                asyncExecutions.incrementAndGet()
                action()
            }
        }
    }

    override fun runAt(location: Location, action: () -> Unit) {
        if (PlatformCleanup.skipsOwnerTasks()) return
        val cleanup = PlatformCleanup.capture(action)
        if (cleanup == null && !lifecycle.isActive()) return
        if (owns(location)) {
            directExecutions.incrementAndGet()
            if (cleanup != null) cleanup.run() else lifecycle.execute(action)
            return
        }
        regionSubmissions.incrementAndGet()
        try {
            Bukkit.getRegionScheduler().execute(plugin, location, Runnable {
                regionExecutions.incrementAndGet()
                if (cleanup != null) cleanup.run() else lifecycle.execute(action)
            })
        } catch (failure: Exception) {
            cleanup?.failed(failure)
            throw failure
        }
    }

    override fun runForEntity(entity: Entity, action: () -> Unit, retired: () -> Unit): Boolean {
        if (PlatformCleanup.skipsOwnerTasks()) return false
        val cleanup = PlatformCleanup.capture(action)
        if (cleanup == null && !lifecycle.isActive()) {
            retired()
            return false
        }
        if (owns(entity)) {
            directExecutions.incrementAndGet()
            if (cleanup != null) cleanup.run() else lifecycle.execute(action)
            return true
        }
        entitySubmissions.incrementAndGet()
        try {
            val accepted = entity.scheduler.execute(plugin, Runnable {
                entityExecutions.incrementAndGet()
                if (cleanup != null) cleanup.run() else if (!lifecycle.execute(action)) retired()
            }, Runnable {
                retiredTasks.incrementAndGet()
                if (cleanup != null) cleanup.retired(retired) else retired()
            }, 0L)
            if (!accepted) cleanup?.retired(retired)
            return accepted
        } catch (failure: Exception) {
            cleanup?.failed(failure)
            throw failure
        }
    }

    override fun runForEntityLater(
        entity: Entity,
        delayTicks: Long,
        action: () -> Unit,
        retired: () -> Unit
    ): ScheduledTask? {
        require(delayTicks > 0L) { "entity scheduler delay must be positive" }
        if (PlatformCleanup.skipsOwnerTasks()) return null
        val cleanup = PlatformCleanup.capture(action)
        if (cleanup == null && !lifecycle.isActive()) {
            retired()
            return null
        }
        entitySubmissions.incrementAndGet()
        try {
            val task = entity.scheduler.runDelayed(plugin, {
                entityExecutions.incrementAndGet()
                if (cleanup != null) cleanup.run() else if (!lifecycle.execute(action)) retired()
            }, Runnable {
                retiredTasks.incrementAndGet()
                if (cleanup != null) cleanup.retired(retired) else retired()
            }, delayTicks)
            if (task == null) cleanup?.retired(retired)
            return task
        } catch (failure: Exception) {
            cleanup?.failed(failure)
            throw failure
        }
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
            if (!lifecycle.execute {
                    entityExecutions.incrementAndGet()
                    action(task)
                }) task.cancel()
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
