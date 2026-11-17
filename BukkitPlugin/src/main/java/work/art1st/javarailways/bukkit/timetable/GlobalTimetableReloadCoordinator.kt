package work.art1st.javarailways.bukkit.timetable

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.World as BukkitWorld
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.http.TimetableScriptNormalizer
import work.art1st.javarailways.bukkit.extension.train.renderer
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.train.CoreTrain
import work.art1st.javarailways.simpleato.persistence.SavedTimetableV1
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.timetable.Timetable
import work.art1st.javarailways.simpleato.timetable.TimetableEpochStartResult
import work.art1st.javarailways.simpleato.timetable.TimetableInitialPositionMode
import work.art1st.javarailways.simpleato.timetable.TimetableInitialPositionResult
import work.art1st.javarailways.simpleato.timetable.TimetableInitialPositioner
import work.art1st.javarailways.simpleato.timetable.TimetableValidator
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

data class GlobalTimetableReloadReport(
    val scriptCount: Int,
    val worldCount: Int,
    val discardedRuns: Int,
    val resetPlacements: Int,
    val epochTick: Long
)

/** Server-wide, administrator-triggered replacement of every stored timetable definition. */
class GlobalTimetableReloadCoordinator(private val plugin: BukkitPlugin) : AutoCloseable {
    private data class Compilation(val scriptName: String, val saved: SavedTimetableV1)
    private data class Target(val bukkit: BukkitWorld, val world: CoreWorld) {
        val id: UUID get() = bukkit.uid
        val label: String get() = "${bukkit.name}(${bukkit.uid})"
    }
    private data class Validation(
        val target: Target,
        val accepted: Map<String, Timetable>,
        val failures: Map<String, String>
    )
    private data class ReloadWorld(
        val target: Target,
        val replacements: List<Timetable>,
        val previousTimetableIds: Set<String>,
        val previousTrainIds: Set<String>
    )
    private data class Applied(val discardedRuns: Int, val placements: Int, val trainResets: List<CompletableFuture<Unit>>)

    private val active = AtomicBoolean()
    @Volatile private var pollingTask: ScheduledTask? = null
    @Volatile private var activeResult: CompletableFuture<GlobalTimetableReloadReport>? = null
    @Volatile private var closed = false

    val isReloading: Boolean get() = active.get()

    fun reload(status: (String) -> Unit = {}): CompletableFuture<GlobalTimetableReloadReport> {
        check(!closed) { "global timetable reload coordinator is closed" }
        check(active.compareAndSet(false, true)) { "a global timetable reload is already running" }
        val result = CompletableFuture<GlobalTimetableReloadReport>()
        activeResult = result
        result.whenComplete { _, _ ->
            pollingTask?.cancel()
            pollingTask = null
            activeResult = null
            active.set(false)
        }
        status("正在读取并编译正式时刻表仓库……")
        try {
            plugin.platformDispatcher.runAsync {
                try {
                    val scripts = plugin.timetableScriptSandbox.snapshotSources()
                    require(scripts.isNotEmpty()) { "正式时刻表仓库为空" }
                    val compiled = scripts.map { script ->
                        val id = script.name.dropLast(3)
                        Compilation(script.name, plugin.timetableScriptSandbox.compileSource(script.source, id))
                    }
                    plugin.platformDispatcher.runGlobal {
                        if (!result.isDone) beginPreflight(compiled, status, result)
                    }
                } catch (failure: Throwable) {
                    result.completeExceptionally(failure)
                }
            }
        } catch (failure: Throwable) {
            result.completeExceptionally(failure)
        }
        return result
    }

    private fun beginPreflight(
        compilations: List<Compilation>,
        status: (String) -> Unit,
        result: CompletableFuture<GlobalTimetableReloadReport>
    ) {
        val targets = loadedTargets()
        if (targets.isEmpty()) {
            result.completeExceptionally(IllegalStateException("服务器中没有已加载的 JavaRailways 世界"))
            return
        }
        status("编译完成；正在根据基础设施归属校验 ${compilations.size} 张时刻表……")
        val validations = targets.map { target ->
            target.world.physicsCoordinator.submitBetweenFrames {
                requireCurrent(target)
                val accepted = LinkedHashMap<String, Timetable>()
                val failures = LinkedHashMap<String, String>()
                compilations.forEach { compilation ->
                    try {
                        val normalized = TimetableScriptNormalizer.normalize(
                            compilation.saved,
                            target.world.simpleAtoWorldData.destinations
                        )
                        val timetable = normalized.restore()
                        val validation = TimetableValidator(target.world).validate(timetable)
                        if (validation.isSuccess) accepted[compilation.scriptName] = timetable
                        else failures[compilation.scriptName] = validation.errors.joinToString("; ")
                    } catch (failure: Exception) {
                        failures[compilation.scriptName] = failure.message ?: failure.javaClass.simpleName
                    }
                }
                Validation(target, accepted, failures)
            }
        }
        all(validations).whenComplete { values, failure ->
            plugin.platformDispatcher.runGlobal {
                if (failure != null) {
                    result.completeExceptionally(rootCause(failure))
                    return@runGlobal
                }
                try {
                    val assignments = compilations.associate { compilation ->
                        val matches = values.filter { compilation.scriptName in it.accepted }
                        require(matches.size == 1) {
                            if (matches.isEmpty()) {
                                buildString {
                                    append("时刻表 ${compilation.scriptName} 不属于任何已加载世界")
                                    values.forEach { validation ->
                                        append("\n- ${validation.target.label}: ")
                                        append(validation.failures[compilation.scriptName] ?: "未通过校验")
                                    }
                                }
                            } else {
                                "时刻表 ${compilation.scriptName} 同时匹配多个世界: " +
                                    matches.joinToString { it.target.label }
                            }
                        }
                        compilation.scriptName to (matches.single().target to
                            matches.single().accepted.getValue(compilation.scriptName))
                    }
                    val reloadWorlds = targets.map { target ->
                        val replacements = assignments.values
                            .filter { (assigned, _) -> assigned.id == target.id }
                            .map { it.second }
                            .sortedBy(Timetable::id)
                        ReloadWorld(target, replacements, emptySet(), emptySet())
                    }
                    requestStops(reloadWorlds, compilations.size, status, result)
                } catch (problem: Throwable) {
                    result.completeExceptionally(problem)
                }
            }
        }
    }

    private fun requestStops(
        worlds: List<ReloadWorld>,
        scriptCount: Int,
        status: (String) -> Unit,
        result: CompletableFuture<GlobalTimetableReloadReport>
    ) {
        status("校验完成；正在请求所有现有列车停车……")
        val stops = worlds.map { reload ->
            reload.target.world.physicsCoordinator.submitBetweenFrames {
                requireCurrent(reload.target)
                val data = reload.target.world.simpleAtoWorldData
                val checkpoint = data.dispatcher.saveCheckpoint()
                val statuses = data.dispatcher.statuses()
                val ids = data.timetables.all().mapTo(LinkedHashSet(), Timetable::id).apply {
                    checkpoint.clocks.mapTo(this) { it.timetableId }
                    checkpoint.runs.mapTo(this) { it.timetableId }
                    statuses.mapTo(this) { it.id.timetableId }
                }
                ids.forEach(data.dispatcher::stopFutureCycles)
                statuses.forEach { run ->
                    data.dispatcher.stopService(reload.target.world, run.id, run.cycleIndex)
                }
                reload.copy(
                    previousTimetableIds = ids,
                    previousTrainIds = statuses.mapTo(LinkedHashSet()) { it.trainId }
                )
            }
        }
        all(stops).whenComplete { stopped, failure ->
            plugin.platformDispatcher.runGlobal {
                if (failure != null) {
                    result.completeExceptionally(rootCause(failure))
                } else {
                    waitForRouteReset(stopped, scriptCount, status, result)
                }
            }
        }
    }

    private fun waitForRouteReset(
        worlds: List<ReloadWorld>, scriptCount: Int, status: (String) -> Unit,
        result: CompletableFuture<GlobalTimetableReloadReport>
    ) {
        status("正在等待列车物理停稳，并强制释放旧进路……")
        var polls = 0
        val inFlight = AtomicBoolean()
        pollingTask = plugin.platformDispatcher.runGlobalAtFixedRate(1L, STOP_POLL_PERIOD_TICKS) {
            if (result.isDone || !inFlight.compareAndSet(false, true)) return@runGlobalAtFixedRate
            if (++polls > MAX_STOP_POLLS) {
                result.completeExceptionally(IllegalStateException("等待旧进路准备操作取消超时；新时刻表未启动"))
                return@runGlobalAtFixedRate
            }
            val attempts = worlds.map { reload ->
                reload.target.world.physicsCoordinator.submitBetweenFrames {
                    requireCurrent(reload.target)
                    reload.previousTimetableIds.isEmpty() || reload.target.world.simpleAtoWorldData.dispatcher
                        .releaseStoppedTimetableRoutes(reload.target.world, reload.previousTimetableIds)
                }
            }
            all(attempts).whenComplete { ready, failure ->
                plugin.platformDispatcher.runGlobal {
                    inFlight.set(false)
                    if (failure != null) result.completeExceptionally(rootCause(failure))
                    else if (ready.all { it }) {
                        pollingTask?.cancel()
                        pollingTask = null
                        applyReplacement(worlds, scriptCount, status, result)
                    }
                }
            }
        }
    }

    private fun applyReplacement(
        worlds: List<ReloadWorld>,
        scriptCount: Int,
        status: (String) -> Unit,
        result: CompletableFuture<GlobalTimetableReloadReport>
    ) {
        status("所有旧运行已停车；正在替换定义并批量复位列车……")
        val applies = worlds.map { reload ->
            reload.target.world.physicsCoordinator.submitBetweenFrames {
                requireCurrent(reload.target)
                val world = reload.target.world
                val data = world.simpleAtoWorldData
                val selectedTrainIds = LinkedHashSet(reload.previousTrainIds)
                world.allTrains.filterIsInstance<CoreTrain>().filter { train ->
                    reload.replacements.any { timetable ->
                        timetable.initialPositions.any { it.trainSelector.matches(train.name) }
                    }
                }.forEach { selectedTrainIds += it.id }
                val discarded = if (reload.previousTimetableIds.isEmpty()) 0 else
                    data.dispatcher.forceDiscardTimetables(world, reload.previousTimetableIds, releaseStoppedRoutes = true)
                selectedTrainIds.forEach { id ->
                    world.getTrain(id)?.let { train ->
                        plugin.trainControlManager.releaseTrain(train)
                        train.setController(null)
                    }
                }
                if (data.atoCoordinator.isStalled) data.atoCoordinator.reset()
                data.timetables.replaceAll(reload.replacements)
                val placement = world.withPhysicsStalled { maintenance ->
                    TimetableInitialPositioner(world, maintenance).apply(
                        reload.replacements,
                        data.dispatcher.saveCheckpoint().dispatcherTick
                    )
                }
                when (placement) {
                    is TimetableInitialPositionResult.Applied -> Applied(discarded, placement.placements,
                        selectedTrainIds.map { id ->
                            requireNotNull(world.getTrain(id)) { "train disappeared during reset: $id" }
                                .renderer.resetOperationalState()
                        })
                    is TimetableInitialPositionResult.Failure -> error(placement.reason)
                }
            }
        }
        all(applies).whenComplete { applied, failure ->
            plugin.platformDispatcher.runGlobal {
                if (failure != null) {
                    result.completeExceptionally(rootCause(failure))
                    return@runGlobal
                }
                status("正在恢复列车模型和脚本的默认状态……")
                all(applied.flatMap { it.trainResets }).whenComplete { _, resetFailure ->
                    plugin.platformDispatcher.runGlobal {
                        if (resetFailure != null) {
                            result.completeExceptionally(rootCause(resetFailure))
                        } else if (!result.isDone) {
                            val epoch = Math.addExact(plugin.worldManager.operationalTick(), START_DELAY_TICKS)
                            beginEpochs(worlds, scriptCount, applied, epoch, status, result)
                        }
                    }
                }
            }
        }
    }

    private fun beginEpochs(
        worlds: List<ReloadWorld>,
        scriptCount: Int,
        applied: List<Applied>,
        epoch: Long,
        status: (String) -> Unit,
        result: CompletableFuture<GlobalTimetableReloadReport>
    ) {
        status("列车复位完成；正在为所有世界建立统一运营 epoch=$epoch……")
        val begins = worlds.map { reload ->
            reload.target.world.physicsCoordinator.submitBetweenFrames {
                requireCurrent(reload.target)
                val dispatcher = reload.target.world.simpleAtoWorldData.dispatcher
                val begun = ArrayList<String>()
                try {
                    reload.replacements.forEach { timetable ->
                        when (val started = dispatcher.beginTimetable(
                            reload.target.world,
                            timetable.id,
                            TimetableInitialPositionMode.KEEP,
                            epoch
                        )) {
                            is TimetableEpochStartResult.Started -> begun += timetable.id
                            is TimetableEpochStartResult.Failure -> error(started.reason)
                        }
                    }
                    begun
                } catch (failure: Throwable) {
                    begun.asReversed().forEach { dispatcher.forceDiscardTimetable(reload.target.world, it) }
                    throw failure
                }
            }
        }
        all(begins).whenComplete { _, failure ->
            plugin.platformDispatcher.runGlobal {
                if (failure != null) {
                    discardNewEpochs(worlds)
                    result.completeExceptionally(rootCause(failure))
                    return@runGlobal
                }
                scheduleInitialCycles(worlds, scriptCount, applied, epoch, status, result)
            }
        }
    }

    private fun scheduleInitialCycles(
        worlds: List<ReloadWorld>,
        scriptCount: Int,
        applied: List<Applied>,
        epoch: Long,
        status: (String) -> Unit,
        result: CompletableFuture<GlobalTimetableReloadReport>
    ) {
        val schedules = worlds.map { reload ->
            reload.target.world.physicsCoordinator.submitBetweenFrames {
                requireCurrent(reload.target)
                reload.replacements.forEach { timetable ->
                    check(reload.target.world.simpleAtoWorldData.dispatcher.scheduleInitialCycle(timetable.id)) {
                        "failed to schedule initial cycle for ${timetable.id}"
                    }
                }
            }
        }
        all(schedules).whenComplete { _, failure ->
            plugin.platformDispatcher.runGlobal {
                if (failure != null) {
                    discardNewEpochs(worlds)
                    result.completeExceptionally(rootCause(failure))
                } else {
                    status("全局时刻表已加载，将在运营 tick $epoch 同步开始")
                    result.complete(GlobalTimetableReloadReport(
                        scriptCount,
                        worlds.size,
                        applied.sumOf(Applied::discardedRuns),
                        applied.sumOf(Applied::placements),
                        epoch
                    ))
                }
            }
        }
    }

    private fun discardNewEpochs(worlds: List<ReloadWorld>) {
        worlds.forEach { reload ->
            reload.target.world.physicsCoordinator.submitBetweenFrames {
                if (plugin.worldManager.loadedRailWorld(reload.target.id) !== reload.target.world) return@submitBetweenFrames
                reload.replacements.forEach { timetable ->
                    runCatching {
                        reload.target.world.simpleAtoWorldData.dispatcher.forceDiscardTimetable(
                            reload.target.world,
                            timetable.id
                        )
                    }
                }
            }
        }
    }

    private fun loadedTargets(): List<Target> = plugin.worldManager.railWorlds.entries
        .mapNotNull { (bukkit, world) -> (world as? CoreWorld)?.let { Target(bukkit, it) } }
        .sortedBy { it.bukkit.name }

    private fun requireCurrent(target: Target) {
        check(plugin.worldManager.loadedRailWorld(target.id) === target.world) {
            "JavaRailways world unloaded or replaced during global timetable reload: ${target.label}"
        }
    }

    override fun close() {
        closed = true
        pollingTask?.cancel()
        pollingTask = null
        activeResult?.completeExceptionally(IllegalStateException("plugin is shutting down"))
    }

    private companion object {
        const val STOP_POLL_PERIOD_TICKS = 5L
        const val MAX_STOP_POLLS = 2_400
        const val START_DELAY_TICKS = 100L
        fun rootCause(failure: Throwable): Throwable =
            generateSequence(failure) { it.cause }.last()

        fun <T> all(futures: List<CompletableFuture<T>>): CompletableFuture<List<T>> =
            CompletableFuture.allOf(*futures.toTypedArray()).thenApply { futures.map(CompletableFuture<T>::join) }
    }
}
