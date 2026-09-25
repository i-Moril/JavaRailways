package work.art1st.javarailways.simpleato.timetable.simulation

import work.art1st.javarailways.basic.BasicPlugin
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.pathFinding.basicWorldData
import work.art1st.javarailways.basic.persistence.BasicInfrastructureSnapshotV1
import work.art1st.javarailways.basic.timetable.Timetable
import work.art1st.javarailways.simpleato.SimpleATOPlugin
import work.art1st.javarailways.simpleato.SimpleATOWorldData
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.timetable.TimetableEpochStartResult
import work.art1st.javarailways.simpleato.timetable.TimetableServiceRunSnapshot
import work.art1st.javarailways.simpleato.timetable.TimetableServiceRunState
import work.art1st.javarailways.core.di.NoOpWorldParentContext
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.util.IsolatedMainThreadMarker
import work.art1st.javarailways.core.util.TickManagerImpl
import java.util.concurrent.Executors

/** Request for one deterministic cycle in a world containing no live runtime state. */
data class IsolatedTimetableSimulationRequest(
    val timetable: Timetable,
    val delayThresholdTicks: Long = 1_200L,
    val maximumSimulationTicks: Long = timetable.periodTicks
) {
    init {
        require(delayThresholdTicks > 0L)
        require(maximumSimulationTicks > 0L && maximumSimulationTicks <= timetable.periodTicks)
    }
}

sealed interface IsolatedTimetableSimulationResult {
    val simulatedTicks: Long
    val runs: List<TimetableServiceRunSnapshot>

    data class Completed(
        override val simulatedTicks: Long,
        override val runs: List<TimetableServiceRunSnapshot>
    ) : IsolatedTimetableSimulationResult

    data class DelayExceeded(
        override val simulatedTicks: Long,
        val thresholdTicks: Long,
        val observedDelayTicks: Long,
        val delayedRun: TimetableServiceRunSnapshot,
        override val runs: List<TimetableServiceRunSnapshot>
    ) : IsolatedTimetableSimulationResult

    data class Failed(
        override val simulatedTicks: Long,
        val reason: String,
        val failedRun: TimetableServiceRunSnapshot? = null,
        override val runs: List<TimetableServiceRunSnapshot> = emptyList()
    ) : IsolatedTimetableSimulationResult

    data class PeriodEnded(
        override val simulatedTicks: Long,
        override val runs: List<TimetableServiceRunSnapshot>
    ) : IsolatedTimetableSimulationResult
}

/**
 * Owns a static Core/Basic world copy and isolated worker pools. Runtime trains must be created by
 * [seed] from timetable profile assignments; no live-world train, reservation, or dispatcher state
 * is restored.
 */
class IsolatedTimetableSimulationWorld private constructor(
    val world: CoreWorld,
    private val tickManager: TickManagerImpl,
    private val physicsCoordinator: PhysicsCoordinator
) : AutoCloseable {
    private var logicalMillis = 0L
    private var hasSimulated = false

    fun simulate(request: IsolatedTimetableSimulationRequest): IsolatedTimetableSimulationResult =
        IsolatedMainThreadMarker.run {
            check(!hasSimulated) { "an isolated simulation world is single-use" }
            hasSimulated = true
            try {
                simulateOnIsolatedThread(request)
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                IsolatedTimetableSimulationResult.Failed(
                    logicalMillis / LOGICAL_TICK_MILLIS,
                    "simulation cancelled",
                    runs = cycleZeroRuns(request.timetable.id)
                )
            } catch (failure: Exception) {
                IsolatedTimetableSimulationResult.Failed(
                    logicalMillis / LOGICAL_TICK_MILLIS,
                    failure.message ?: failure::class.java.simpleName,
                    runs = cycleZeroRuns(request.timetable.id)
                )
            }
        }

    private fun simulateOnIsolatedThread(
        request: IsolatedTimetableSimulationRequest
    ): IsolatedTimetableSimulationResult {
        val data = world.basicWorldData
        val dispatcher = world.simpleAtoWorldData.dispatcher
        data.timetables.add(request.timetable)
        when (val started = dispatcher.beginTimetable(world, request.timetable.id)) {
            is TimetableEpochStartResult.Failure -> return IsolatedTimetableSimulationResult.Failed(
                0L, started.reason
            )
            is TimetableEpochStartResult.Started -> Unit
        }
        if (!dispatcher.scheduleInitialCycle(request.timetable.id)) {
            return IsolatedTimetableSimulationResult.Failed(0L, "could not schedule initial timetable cycle")
        }

        for (tick in 1L..request.maximumSimulationTicks) {
            if (Thread.currentThread().isInterrupted) {
                return IsolatedTimetableSimulationResult.Failed(
                    tick - 1L,
                    "simulation cancelled",
                    runs = cycleZeroRuns(request.timetable.id)
                )
            }
            step()
            val runs = cycleZeroRuns(request.timetable.id)
            runs.firstOrNull { it.state == TimetableServiceRunState.FAILED }?.let { failed ->
                return IsolatedTimetableSimulationResult.Failed(
                    tick,
                    failed.message ?: "service failed",
                    failed,
                    runs
                )
            }
            runs.map { run -> run to run.observedDelayTicks() }
                .maxByOrNull { it.second }
                ?.takeIf { it.second >= request.delayThresholdTicks }
                ?.let { (delayed, observedDelay) ->
                    return IsolatedTimetableSimulationResult.DelayExceeded(
                        tick,
                        request.delayThresholdTicks,
                        observedDelay,
                        delayed,
                        runs
                    )
                }
            if (runs.size == request.timetable.services.size && runs.all { it.isSuccessfulTerminal() }) {
                return IsolatedTimetableSimulationResult.Completed(tick, runs)
            }
        }
        return IsolatedTimetableSimulationResult.PeriodEnded(
            request.maximumSimulationTicks,
            cycleZeroRuns(request.timetable.id)
        )
    }

    private fun step() {
        logicalMillis = Math.addExact(logicalMillis, LOGICAL_TICK_MILLIS)
        tickManager.preTick(logicalMillis)
        physicsCoordinator.stepSimulation(LOGICAL_TICK_MILLIS.toDouble()).await()
        world.simpleAtoWorldData.dispatcher.tick(world)
        tickManager.postTick()
        tickManager.drainPendingCommits().forEach { it.commit() }
    }

    private fun cycleZeroRuns(timetableId: String): List<TimetableServiceRunSnapshot> =
        world.simpleAtoWorldData.dispatcher.statuses()
            .filter { it.id.timetableId == timetableId && it.cycleIndex == 0L }
            .sortedBy { it.id.serviceId }

    override fun close() {
        physicsCoordinator.close()
        tickManager.close()
    }

    companion object {
        private const val LOGICAL_TICK_MILLIS = 50L

        fun create(
            coreInfrastructure: CoreWorld.SavedInfrastructureV2,
            basicInfrastructure: BasicInfrastructureSnapshotV1,
            workerThreads: Int,
            basicDataFactory: (CoreWorld) -> BasicWorldData,
            simpleAtoDataFactory: (CoreWorld) -> SimpleATOWorldData,
            seed: (CoreWorld) -> Unit
        ): IsolatedTimetableSimulationWorld {
            require(workerThreads > 0)
            require(coreInfrastructure.infrastructureRevision == basicInfrastructure.infrastructureRevision)
            val tickManager = TickManagerImpl(
                Executors.newScheduledThreadPool(workerThreads) { runnable ->
                    Thread(runnable, "JavaRailways-SimulationTick").apply { isDaemon = true }
                },
                0L
            )
            val physics = PhysicsCoordinator(
                workerThreads = workerThreads,
                epochOverloadThresholdMillis = 60_000L,
                overloadWindowMillis = 60_000L,
                overloadsBeforeStall = Int.MAX_VALUE,
                automaticFallback = false,
                publishCollisionEvents = false
            )
            try {
                val world = IsolatedMainThreadMarker.run {
                    CoreWorld.load(
                        coreInfrastructure,
                        runtime = null,
                        dependency = CoreWorld.SnapshotDependency(
                            tickManager,
                            NoOpWorldParentContext,
                            physics
                        )
                    ).also { isolatedWorld ->
                        isolatedWorld.registerExtensionData(
                            BasicPlugin.INSTANCE,
                            basicDataFactory(isolatedWorld)
                        )
                        isolatedWorld.registerExtensionData(
                            SimpleATOPlugin.INSTANCE,
                            simpleAtoDataFactory(isolatedWorld)
                        )
                        basicInfrastructure.restore(isolatedWorld)
                        seed(isolatedWorld)
                    }
                }
                physics.start()
                return IsolatedTimetableSimulationWorld(world, tickManager, physics)
            } catch (failure: Throwable) {
                physics.close()
                tickManager.close()
                throw failure
            }
        }
    }
}

private fun TimetableServiceRunSnapshot.isSuccessfulTerminal(): Boolean =
    state == TimetableServiceRunState.COMPLETED || state == TimetableServiceRunState.STOPPED

private fun TimetableServiceRunSnapshot.observedDelayTicks(): Long = maxOf(
    lastLatenessTicks,
    nextScheduledTick?.let { (timetableElapsedTicks - it).coerceAtLeast(0L) } ?: 0L
)
