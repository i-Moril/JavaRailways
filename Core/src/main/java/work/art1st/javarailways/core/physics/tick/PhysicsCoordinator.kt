package work.art1st.javarailways.core.physics.tick

import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.api.event.CollisionEvent
import work.art1st.javarailways.core.api.event.PreTickEvent
import work.art1st.javarailways.core.api.event.PostTickEvent
import work.art1st.javarailways.core.api.event.CollisionResolution
import work.art1st.javarailways.core.api.event.processor.PhysicsRailEventBus
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.physics.RailwayEpochProcessor
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.api.threading.ThreadGuard
import work.art1st.javarailways.core.api.util.annotation.AsyncEventApi
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.train.CouplingRejectedException
import work.art1st.javarailways.core.physics.train.CoreTrain
import work.art1st.javarailways.core.physics.train.CollisionEnvelope
import work.art1st.javarailways.core.util.AsyncTickThreadMarker
import work.art1st.javarailways.core.util.awaitExecutorTermination
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger

/** Identifies who caused a physics epoch to start. */
enum class PhysicsWakeSource {
    MAIN_THREAD,
    TIMER_FALLBACK,
    SIMULATION
}

enum class PhysicsCoordinatorStatus {
    RUNNING,
    STALLED,
    CLOSED
}

@ConsistentCopyVisibility
data class CollisionPair private constructor(val firstTrainId: String, val secondTrainId: String) {
    companion object {
        fun of(trainAId: String, trainBId: String): CollisionPair =
            if (trainAId <= trainBId) CollisionPair(trainAId, trainBId) else CollisionPair(trainBId, trainAId)
    }
}

private data class PendingCollisionDecision(
    val detectedEpoch: Long,
    val pair: CollisionPair,
    val resolution: CollisionResolution,
    val firstTrain: CoreTrain,
    val secondTrain: CoreTrain,
    val firstFrame: TrainFrameSnapshot,
    val secondFrame: TrainFrameSnapshot,
    val topologyRevision: Long?,
    val firstEnvelope: CollisionEnvelope?,
    val secondEnvelope: CollisionEnvelope?
)

private class BetweenFramesOperation<T>(val action: () -> T) {
    val result = CompletableFuture<T>()

    fun execute() {
        if (result.isDone) return
        try {
            result.complete(action())
        } catch (failure: Throwable) {
            result.completeExceptionally(failure)
        }
    }
}

data class PhysicsEpochMetrics(
    val epoch: Long,
    val source: PhysicsWakeSource,
    /** Delta passed to controllers after wall-clock clamping. */
    val deltaMillis: Double,
    val rawDeltaMillis: Double,
    val droppedDeltaMillis: Double,
    val deltaClamped: Boolean,
    val clampedEpochCount: Long,
    val wallNanos: Long,
    val workerMaxNanos: Long,
    val workerTotalNanos: Long,
    val coordinatorNanos: Long,
    val activeTrainCount: Int,
    val totalTrainCount: Int,
    val spanCount: Int,
    val bucketReferenceCount: Int,
    val candidateChecks: Long,
    val collisionClaims: Int,
    val overloadCountInWindow: Int
)

open class PhysicsStalledException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** Recoverable local overload; its cause remains identifiable after a manual reset. */
class PhysicsOverloadException(message: String) : PhysicsStalledException(message)

/**
 * A handle for one concrete physics epoch. Tickets never silently resolve to an older epoch.
 */
class PhysicsTicket internal constructor(
    val epoch: Long,
    internal val completion: CompletableFuture<PhysicsEpochMetrics>
) {
    fun await(): PhysicsEpochMetrics = completion.get()
    val isDone: Boolean get() = completion.isDone
}

/**
 * Coordinates train controllers independently from Bukkit's generic animation TickManager.
 *
 * There is at most one in-flight epoch. Main-thread and timer wakeups therefore coalesce onto the
 * same [PhysicsTicket] instead of creating a backlog.
 */
class PhysicsCoordinator(
    workerThreads: Int,
    private val nanoTime: () -> Long = System::nanoTime,
    private val fallbackPeriodMillis: Long = FALLBACK_PERIOD_MILLIS,
    private val mainHeartbeatTimeoutMillis: Long = MAIN_HEARTBEAT_TIMEOUT_MILLIS,
    private val epochOverloadThresholdMillis: Long = DEFAULT_EPOCH_OVERLOAD_THRESHOLD_MILLIS,
    private val maxPhysicsDeltaMillis: Double = DEFAULT_MAX_PHYSICS_DELTA_MILLIS,
    private val overloadWindowMillis: Long = OVERLOAD_WINDOW_MILLIS,
    private val overloadsBeforeStall: Int = OVERLOADS_BEFORE_STALL,
    private val automaticFallback: Boolean = true
) : AutoCloseable {
    init {
        require(workerThreads > 0) { "physics workerThreads must be > 0" }
        require(fallbackPeriodMillis > 0) { "fallbackPeriodMillis must be > 0" }
        require(mainHeartbeatTimeoutMillis > 0) { "mainHeartbeatTimeoutMillis must be > 0" }
        require(epochOverloadThresholdMillis > 0) { "epochOverloadThresholdMillis must be > 0" }
        require(maxPhysicsDeltaMillis > 0.0 && maxPhysicsDeltaMillis.isFinite()) {
            "maxPhysicsDeltaMillis must be finite and > 0"
        }
        require(overloadWindowMillis > 0) { "overloadWindowMillis must be > 0" }
        require(overloadsBeforeStall > 0) { "overloadsBeforeStall must be > 0" }
    }

    /** Read-only timing contract shared with controllers; the scheduler still uses milliseconds. */
    val maximumPhysicsDeltaSeconds: Double = maxPhysicsDeltaMillis * 0.001

    private val workerCount = workerThreads
    private val workerPool: ExecutorService = Executors.newFixedThreadPool(workerThreads) { runnable ->
        Thread(runnable, "JavaRailways-PhysicsWorker-${WORKER_SEQUENCE.incrementAndGet()}").apply { isDaemon = true }
    }
    private val coordinatorExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "JavaRailways-PhysicsCoordinator-${COORDINATOR_SEQUENCE.incrementAndGet()}").apply { isDaemon = true }
    }
    private val fallbackTimer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "JavaRailways-PhysicsTimer-${TIMER_SEQUENCE.incrementAndGet()}").apply { isDaemon = true }
    }

    internal val executionOwner = work.art1st.javarailways.core.physics.WorldExecutionOwner()
    private val stateLock = Any()
    private val stopLock = Any()
    private val betweenFramesQueue = ArrayDeque<BetweenFramesOperation<*>>()
    private val pendingBetweenFrames = linkedSetOf<BetweenFramesOperation<*>>()
    private val betweenFramesDepth = ThreadLocal.withInitial { 0 }
    private var processingBetweenFrames = false
    private var betweenFramesDrained = false
    private val controllers = ConcurrentHashMap<String, TrainController>()
    private val publishedFrameProcessors = CopyOnWriteArrayList<PublishedTrainFrameProcessor>()
    private val railwayEpochProcessors = CopyOnWriteArrayList<RailwayEpochProcessor>()
    private val trainsPublishingMotionFrames = ConcurrentHashMap.newKeySet<String>()
    private val overloadTimesNanos = ArrayDeque<Long>()
    private val collisionClaims = ConcurrentHashMap.newKeySet<CollisionPair>()
    private val pendingCollisionDecisions = ConcurrentHashMap<CollisionPair, PendingCollisionDecision>()
    private val trainsPendingCollision = ConcurrentHashMap.newKeySet<String>()
    private val epochCandidateChecks = AtomicLong()
    private var clampedEpochCount = 0L
    private var nextClampLogNanos = 0L

    @Volatile
    private var runningEpoch: Long = 0L

    @Volatile
    private var world: CoreWorld? = null

    @Volatile
    private var localStatus: PhysicsCoordinatorStatus = PhysicsCoordinatorStatus.RUNNING
    @Volatile
    private var manualStallDepth = 0

    var status: PhysicsCoordinatorStatus
        get() = when {
            localStatus == PhysicsCoordinatorStatus.CLOSED -> PhysicsCoordinatorStatus.CLOSED
            RailwaySimulationHalt.isHalted || manualStallDepth > 0 -> PhysicsCoordinatorStatus.STALLED
            else -> localStatus
        }
        private set(value) { localStatus = value }

    /** Maintenance is usable owner time; a real failure or close must preserve failed state. */
    internal val isUsableForOwnerOperations: Boolean
        get() = localStatus == PhysicsCoordinatorStatus.RUNNING && !RailwaySimulationHalt.isHalted

    @Volatile
    private var lastMetrics: PhysicsEpochMetrics? = null

    @Volatile
    private var lastControllerNanos: Map<String, Long> = emptyMap()

    private var started = false
    private var acceptingWakes = true
    private var structuralMutationDepth = 0
    private var nextEpoch = 1L
    private var previousPhysicsStartNanos = 0L
    private var lastMainWakeNanos = nanoTime()
    private var inFlight: PhysicsTicket? = null
    private var latestCompleted: PhysicsTicket = completedBootstrapTicket()
    private var overloadRecoveryFrame: RailwayWorldFrameSnapshot? = null
    private val pendingResets = linkedSetOf<CompletableFuture<Boolean>>()

    init { RailwaySimulationHalt.register(this) }

    fun attachWorld(world: CoreWorld) {
        synchronized(stateLock) {
            check(this.world == null) { "PhysicsCoordinator is already attached to a world" }
            this.world = world
        }
    }

    fun start() {
        synchronized(stateLock) {
            ensureUsableLocked()
            check(acceptingWakes) { "PhysicsCoordinator shutdown has begun" }
            if (started) return
            check(world != null) { "PhysicsCoordinator must be attached to a world before start" }
            world!!.rebuildSweptOccupancyIndex(0L)
            started = true
            if (automaticFallback) {
                fallbackTimer.scheduleAtFixedRate(
                    ::timerWake,
                    fallbackPeriodMillis,
                    fallbackPeriodMillis,
                    TimeUnit.MILLISECONDS
                )
            }
        }
    }

    /**
     * Resumes a local overload stall after its epoch has fully left the coordinator executor.
     * Train/controller/collision state is preserved. The failed ticket stays exceptional; a new
     * boundary ticket represents the recovered world publication, without replaying that epoch.
     * Shutdown, close and the process-wide invariant halt cannot be reset here.
     */
    fun resetStall(): CompletableFuture<Boolean> {
        val result = CompletableFuture<Boolean>()
        synchronized(stateLock) { pendingResets.add(result) }
        try {
            coordinatorExecutor.execute {
                try {
                    result.complete(resetOverloadStall())
                } catch (failure: Throwable) {
                    result.completeExceptionally(failure)
                } finally {
                    synchronized(stateLock) { pendingResets.remove(result) }
                }
            }
        } catch (failure: java.util.concurrent.RejectedExecutionException) {
            synchronized(stateLock) { pendingResets.remove(result) }
            result.completeExceptionally(failure)
        }
        return result
    }

    // Like ordinary epoch publication, recovery takes topology before state and never takes
    // the host owner: world teardown may hold that owner while awaiting this executor's exit.
    private fun resetOverloadStall(): Boolean {
        val attachedWorld = synchronized(stateLock) {
            RailwaySimulationHalt.ensureRunning()
            check(localStatus != PhysicsCoordinatorStatus.CLOSED) { "PhysicsCoordinator is closed" }
            check(acceptingWakes) { "PhysicsCoordinator shutdown has begun" }
            if (localStatus == PhysicsCoordinatorStatus.RUNNING) return false
            checkNotNull(world)
        }
        attachedWorld.withRailTopologyReadLock {
            synchronized(stateLock) {
                RailwaySimulationHalt.ensureRunning()
                check(localStatus != PhysicsCoordinatorStatus.CLOSED) { "PhysicsCoordinator is closed" }
                check(acceptingWakes) { "PhysicsCoordinator shutdown has begun" }
                val frame = checkNotNull(overloadRecoveryFrame) { "Physics stall is not recoverable" }
                attachedWorld.publishCapturedWorldFrame(frame)
                latestCompleted = PhysicsTicket(frame.physicsEpoch,
                    CompletableFuture.completedFuture(checkNotNull(lastMetrics)))
                overloadRecoveryFrame = null
                overloadTimesNanos.clear()
                previousPhysicsStartNanos = 0L
                lastMainWakeNanos = nanoTime()
                status = PhysicsCoordinatorStatus.RUNNING
                applyPendingCollisionDecisions(frame.physicsEpoch)
            }
        }
        return true
    }

    /** Owner-side replacement, or physics-origin retirement at the publication barrier. */
    internal fun replaceController(train: CoreTrain, previous: TrainController?, next: TrainController?) {
        check(controllers[train.id] === previous) { "Controller registration changed for '${train.id}'" }
        if (next == null) {
            if (previous != null) controllers.remove(train.id, previous)
            trainsPublishingMotionFrames.remove(train.id)
        } else {
            controllers[train.id] = next
        }
    }

    fun registerPublishedFrameProcessor(processor: PublishedTrainFrameProcessor) {
        publishedFrameProcessors.addIfAbsent(processor)
    }

    fun unregisterPublishedFrameProcessor(processor: PublishedTrainFrameProcessor) {
        publishedFrameProcessors.remove(processor)
    }

    /** Registers serialized value-only work in deterministic registration order. */
    fun registerRailwayEpochProcessor(processor: RailwayEpochProcessor) {
        synchronized(stateLock) {
            check(!RailwaySimulationHalt.isHalted && localStatus == PhysicsCoordinatorStatus.RUNNING) {
                "railway epoch processors cannot be registered after coordinator stall or close"
            }
            check(!started) { "railway epoch processors must be registered before coordinator start" }
            railwayEpochProcessors.addIfAbsent(processor)
        }
    }

    fun unregisterRailwayEpochProcessor(processor: RailwayEpochProcessor) {
        synchronized(stateLock) {
            check(!started || RailwaySimulationHalt.isHalted || localStatus != PhysicsCoordinatorStatus.RUNNING) {
                "railway epoch processors remain static while the coordinator is running"
            }
            railwayEpochProcessors.remove(processor)
        }
    }

    fun wakeFromMainThread(): PhysicsTicket {
        synchronized(stateLock) {
            lastMainWakeNanos = nanoTime()
        }
        return requestStep(PhysicsWakeSource.MAIN_THREAD)
    }

    /** Advances an isolated world by one deterministic logical step without consulting wall time. */
    fun stepSimulation(deltaMillis: Double): PhysicsTicket {
        require(deltaMillis > 0.0 && deltaMillis.isFinite())
        require(deltaMillis <= maxPhysicsDeltaMillis) {
            "stepSimulation deltaMillis must be <= maxPhysicsDeltaMillis ($maxPhysicsDeltaMillis)"
        }
        check(!automaticFallback) { "stepSimulation requires a coordinator with automaticFallback=false" }
        return requestStep(PhysicsWakeSource.SIMULATION, deltaMillis)
    }

    /**
     * Captures exactly the ticket which is running at the time of this call. If physics is idle,
     * the latest completed ticket is returned. Callers must capture once and await that ticket.
     */
    fun captureCurrentTicket(): PhysicsTicket = synchronized(stateLock) {
        ensureUsableLocked()
        inFlight ?: latestCompleted
    }

    /**
     * Queues owner work in FIFO order, including before [start]. Submission never runs the action.
     * The host must call [processBetweenFrames]; success means the action finished, not that its
     * changes have appeared in a later physics frame. Do not await a queued result on a physics
     * worker or inside the owner processing scope. Shutdown, stall and close fail unexecuted work.
     */
    fun <T> submitBetweenFrames(action: () -> T): CompletableFuture<T> {
        val operation = BetweenFramesOperation(action)
        val rejection = synchronized(stateLock) {
            when {
                RailwaySimulationHalt.isHalted || localStatus != PhysicsCoordinatorStatus.RUNNING ->
                    PhysicsStalledException("PhysicsCoordinator is ${status.name.lowercase()}")
                !acceptingWakes -> IllegalStateException("PhysicsCoordinator shutdown has begun")
                else -> {
                    betweenFramesQueue.addLast(operation)
                    pendingBetweenFrames.add(operation)
                    null
                }
            }
        }
        if (rejection != null) operation.result.completeExceptionally(rejection)
        return operation.result
    }

    /**
     * Runs host work on the calling simulation owner after the exact physics ticket completes.
     * The host calls [drainBetweenFramesOperations] once within this callback, then dispatches its
     * remaining owner work. The same structural admission gate covers the wait and whole callback;
     * no callback owns [stateLock]. Host callback failures propagate to the host.
     */
    fun <T> processBetweenFrames(action: (PhysicsTicket) -> T): T {
        ThreadGuard.ensureMainThread("PhysicsCoordinator.processBetweenFrames")
        return executionOwner.run {
            synchronized(stateLock) {
                check(!processingBetweenFrames) { "Between-frames processing is already active" }
                processingBetweenFrames = true
                betweenFramesDrained = false
            }
            try {
                withFrameBoundary(action)
            } finally {
                synchronized(stateLock) { processingBetweenFrames = false }
            }
        }
    }

    /**
     * Applies one finite FIFO batch inside [processBetweenFrames], on that scope's owner.
     * Nested submissions are deferred to the next scope. Each ordinary failure completes only
     * its own future. Captured but unexecuted work remains subject to shutdown/stall/close.
     * Completion callbacks execute outside [stateLock].
     */
    fun drainBetweenFramesOperations() {
        ensureBetweenFrames("PhysicsCoordinator.drainBetweenFramesOperations")
        val batch = synchronized(stateLock) {
            ensureUsableLocked()
            check(acceptingWakes) { "PhysicsCoordinator shutdown has begun" }
            check(processingBetweenFrames && !betweenFramesDrained) {
                "Between-frames operations must be drained once per processing scope"
            }
            betweenFramesDrained = true
            betweenFramesQueue.toList().also { betweenFramesQueue.clear() }
        }
        batch.forEach { operation ->
            synchronized(stateLock) {
                ensureUsableLocked()
                check(acceptingWakes) { "PhysicsCoordinator shutdown has begun" }
                pendingBetweenFrames.remove(operation)
            }
            operation.execute()
        }
        synchronized(stateLock) {
            ensureUsableLocked()
            check(acceptingWakes) { "PhysicsCoordinator shutdown has begun" }
        }
    }

    /** Executes immediately only inside this coordinator's admitted owner phase. */
    internal fun <T> executeBetweenFrames(action: () -> T): CompletableFuture<T> =
        if (betweenFramesDepth.get() > 0) {
            try { CompletableFuture.completedFuture(action()) }
            catch (failure: Throwable) { CompletableFuture.failedFuture(failure) }
        } else submitBetweenFrames(action)

    /** Asserts owner execution after the captured physics ticket completed, without scheduling. */
    fun ensureBetweenFrames(operation: String) {
        ThreadGuard.ensureMainThread(operation)
        RailwaySimulationHalt.ensureRunning()
        check(betweenFramesDepth.get() > 0) { "$operation must run between physics frames" }
    }

    /**
     * Holds new-epoch admission closed across a main-owner structural edit. Waiting and the edit
     * run outside [stateLock], so the captured epoch can finish and callbacks never own that lock.
     * Implementation-layer platform adapters can enclose a synchronous compound operation here;
     * nested owner calls reuse the same phase without draining queued requests. Host processing
     * still uses [processBetweenFrames], whose callback must not be nested or self-wait queued work.
     * This is not shutdown or rollback: finally only releases this temporary admission scope.
     */
    fun <T> withStructuralMutation(action: () -> T): T {
        ThreadGuard.ensureMainThread("PhysicsCoordinator.withStructuralMutation")
        return executionOwner.run { withFrameBoundary { action() } }
    }

    /**
     * Stops epoch admission for synchronous maintenance on this World's simulation owner.
     * The exact in-flight ticket must finish successfully before [action] runs; no ordinary
     * queued work is drained. [status] reports STALLED only for the lexical maintenance scope,
     * while owner reads/publication and nested maintenance remain legal. A thrown action releases
     * only its own scope, and never clears an overload, invariant halt, shutdown or close.
     * Waiting and [action] execute outside [stateLock]; the outermost exit resets wall-clock delta.
     */
    fun <T> withPhysicsStalled(action: () -> T): T {
        ThreadGuard.ensureMainThread("PhysicsCoordinator.withPhysicsStalled")
        return executionOwner.run {
            withFrameBoundary {
                synchronized(stateLock) {
                    ensureUsableLocked()
                    manualStallDepth++
                }
                try {
                    action()
                } finally {
                    synchronized(stateLock) {
                        manualStallDepth--
                        if (manualStallDepth == 0) {
                            previousPhysicsStartNanos = 0L
                            lastMainWakeNanos = nanoTime()
                        }
                    }
                }
            }
        }
    }

    /** Asserts this owner's active maintenance scope, without scheduling or entering a scope. */
    fun ensurePhysicsStalled(operation: String) {
        ensureBetweenFrames(operation)
        synchronized(stateLock) {
            ensureUsableLocked()
            check(acceptingWakes) { "PhysicsCoordinator shutdown has begun" }
            check(manualStallDepth > 0) { "$operation requires physics to be stalled for maintenance" }
        }
    }

    private fun <T> withFrameBoundary(action: (PhysicsTicket) -> T): T {
        val ticket = synchronized(stateLock) {
            ensureUsableLocked()
            check(acceptingWakes) { "PhysicsCoordinator shutdown has begun" }
            structuralMutationDepth++
            inFlight ?: latestCompleted
        }
        try {
            ticket.await()
            synchronized(stateLock) {
                ensureUsableLocked()
                check(acceptingWakes) { "PhysicsCoordinator shutdown has begun" }
            }
            betweenFramesDepth.set(betweenFramesDepth.get() + 1)
            return try {
                action(ticket)
            } finally {
                val depth = betweenFramesDepth.get() - 1
                if (depth == 0) betweenFramesDepth.remove() else betweenFramesDepth.set(depth)
            }
        } finally {
            synchronized(stateLock) { structuralMutationDepth-- }
        }
    }

    /**
     * Permanently stops new epochs and captures the exact in-flight or latest completed ticket.
     * This does not cancel work or wait: the owner must await this ticket successfully before
     * capturing its final snapshot, then [close] the coordinator. A failed/stalled/closed
     * coordinator cannot provide a previous successful ticket as a safe shutdown boundary.
     */
    fun beginShutdown(): PhysicsTicket {
        val (ticket, pending) = synchronized(stateLock) {
            ensureUsableLocked()
            acceptingWakes = false
            (inFlight ?: latestCompleted) to removePendingBetweenFramesLocked()
        }
        failBetweenFrames(pending, IllegalStateException("PhysicsCoordinator shutdown has begun"))
        return ticket
    }

    fun latestMetrics(): PhysicsEpochMetrics? = lastMetrics

    fun latestControllerDurationsNanos(): Map<String, Long> = lastControllerNanos

    /**
     * Fail closed after a host-side invariant breach outside the normal epoch pipeline.
     * The caller owns recovery; this coordinator will not publish another physics epoch.
     */
    internal fun stall(message: String, cause: Throwable? = null): PhysicsStalledException {
        return RailwaySimulationHalt.halt(message, cause)
    }

    /** Queue terminal settlement behind in-flight work; never acquire another world's lock. */
    internal fun requestGlobalHalt(failure: PhysicsStalledException) {
        try {
            coordinatorExecutor.execute { settleHalt(failure) }
        } catch (closed: java.util.concurrent.RejectedExecutionException) {
            // Close may retire a coordinator after the global registry captured it.
            if (localStatus != PhysicsCoordinatorStatus.CLOSED) throw closed
        }
    }

    private fun settleHalt(failure: PhysicsStalledException) {
        val (ticket, pending) = synchronized(stateLock) {
            if (localStatus == PhysicsCoordinatorStatus.CLOSED) return
            status = PhysicsCoordinatorStatus.STALLED
            acceptingWakes = false
            val ticket = inFlight
            inFlight = null
            ticket to removePendingBetweenFramesLocked()
        }
        ticket?.completion?.completeExceptionally(failure)
        failBetweenFrames(pending, failure)
    }

    /** Coordinator prepass over the previous publication; resolution is applied next epoch. */
    @OptIn(AsyncEventApi::class)
    internal fun detectSweptCollision(train: CoreTrain): Boolean {
        if (train.isDerailedForPhysics) return false
        if (train.id in trainsPendingCollision) return true
        val attachedWorld = world ?: return false
        val result = attachedWorld.querySweptCollision(train)
        epochCandidateChecks.addAndGet(result.checkedCandidates.toLong())
        val otherId = result.collidingTrainId ?: return false
        val other = attachedWorld.getTrain(otherId) ?: return false
        val pair = CollisionPair.of(train.id, other.id)
        trainsPendingCollision.add(pair.firstTrainId)
        trainsPendingCollision.add(pair.secondTrainId)

        if (collisionClaims.add(pair)) {
            val first = if (train.id == pair.firstTrainId) train else other
            val second = if (first === train) other else train
            val firstFrame = first.publishedFrame
            val secondFrame = second.publishedFrame
            val worldFrame = attachedWorld.latestWorldFrame()
            val firstEnvelope = attachedWorld.collisionEnvelope(first.id)
            val secondEnvelope = attachedWorld.collisionEnvelope(second.id)
            val topologyRevision = worldFrame.topologyRevision.takeIf {
                worldFrame.physicsEpoch == firstFrame.physicsEpoch && firstFrame.physicsEpoch == secondFrame.physicsEpoch &&
                    worldFrame.trains.any { it === firstFrame } && worldFrame.trains.any { it === secondFrame }
            }
            val event = CollisionEvent(
                epoch = runningEpoch,
                world = attachedWorld,
                trainAId = pair.firstTrainId,
                trainBId = pair.secondTrainId,
                relativeSpeed = relativeSpeed(train, other)
            )
            PhysicsRailEventBus.publish(event)
            pendingCollisionDecisions[pair] = PendingCollisionDecision(
                runningEpoch, pair, event.resolution, first, second, firstFrame, secondFrame, topologyRevision,
                firstEnvelope, secondEnvelope
            )
        }
        return true
    }

    private fun runPublishedFrameProcessors(trains: List<CoreTrain>, physicsEpoch: Long) {
        val processors = publishedFrameProcessors.toList()
        if (processors.isEmpty()) return
        val futures = partition(trains, workerCount).map { batch ->
            workerPool.submit {
                AsyncTickThreadMarker.runInAsyncTickContext {
                    batch.forEach { train ->
                        processors.forEach { it.process(train, physicsEpoch) }
                    }
                }
            }
        }
        awaitWorkers(futures)
        val liveTrainIds = trains.mapTo(linkedSetOf(), CoreTrain::id)
        processors.forEach { it.completeEpoch(liveTrainIds, physicsEpoch) }
    }

    private fun shouldPublishMotionFrame(
        train: CoreTrain,
        previousFrame: work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
    ): Boolean {
        if (train.speed > 0.0) {
            trainsPublishingMotionFrames.add(train.id)
            return true
        }
        return trainsPublishingMotionFrames.remove(train.id) ||
            previousFrame.trainName != train.name ||
            previousFrame.derailed != train.isDerailed ||
            previousFrame.reversed != train.isReversed
    }

    private fun relativeSpeed(first: CoreTrain, second: CoreTrain): Double {
        val firstVelocity = org.joml.Vector3d(first.headLocation.tangent).mul(first.speed)
        val secondVelocity = org.joml.Vector3d(second.headLocation.tangent).mul(second.speed)
        return firstVelocity.sub(secondVelocity).length()
    }

    @JvmSynthetic
    internal fun timerWake() {
        synchronized(stateLock) {
            if (!started || !acceptingWakes || structuralMutationDepth > 0 ||
                RailwaySimulationHalt.isHalted || localStatus != PhysicsCoordinatorStatus.RUNNING) return
            if (nanoTime() - lastMainWakeNanos >= TimeUnit.MILLISECONDS.toNanos(mainHeartbeatTimeoutMillis)) {
                // Keep eligibility and request allocation under the same state lock: shutdown
                // either captures this timer epoch or makes this wake a quiet no-op.
                requestStep(PhysicsWakeSource.TIMER_FALLBACK)
            }
        }
    }

    private fun requestStep(source: PhysicsWakeSource, fixedDeltaMillis: Double? = null): PhysicsTicket {
        val ticket: PhysicsTicket
        val startNanos: Long
        val rawDeltaMillis: Double
        val deltaMillis: Double
        synchronized(stateLock) {
            ensureUsableLocked()
            check(acceptingWakes) { "PhysicsCoordinator shutdown has begun" }
            inFlight?.let { return it }
            check(started) { "PhysicsCoordinator has not been started" }
            if (structuralMutationDepth > 0) return latestCompleted

            startNanos = nanoTime()
            rawDeltaMillis = fixedDeltaMillis ?: if (previousPhysicsStartNanos == 0L) {
                BOOTSTRAP_DELTA_MILLIS
            } else {
                (startNanos - previousPhysicsStartNanos).toDouble() / 1_000_000.0
            }
            require(rawDeltaMillis > 0.0 && rawDeltaMillis.isFinite()) {
                "physics deltaMillis must be finite and > 0"
            }
            deltaMillis = if (fixedDeltaMillis == null) {
                minOf(rawDeltaMillis, maxPhysicsDeltaMillis)
            } else {
                rawDeltaMillis
            }
            if (fixedDeltaMillis == null) previousPhysicsStartNanos = startNanos

            ticket = PhysicsTicket(nextEpoch++, CompletableFuture())
            inFlight = ticket
            coordinatorExecutor.execute {
                runEpoch(ticket, source, startNanos, rawDeltaMillis, deltaMillis)
            }
        }
        return ticket
    }

    @OptIn(AsyncEventApi::class)
    private fun runEpoch(
        ticket: PhysicsTicket,
        source: PhysicsWakeSource,
        epochStartNanos: Long,
        rawDeltaMillis: Double,
        deltaMillis: Double
    ) {
        try {
            val attachedWorld = synchronized(stateLock) {
                if (RailwaySimulationHalt.isHalted || localStatus != PhysicsCoordinatorStatus.RUNNING || inFlight !== ticket) return
                checkNotNull(world)
            }
            AsyncTickThreadMarker.runInAsyncTickContext {
                PhysicsRailEventBus.publish(PreTickEvent(attachedWorld, ticket.epoch, deltaMillis))
            }
            runningEpoch = ticket.epoch
            collisionClaims.clear()
            epochCandidateChecks.set(0L)
            val snapshot = controllers.values.sortedBy { it.profileId }
            // Freeze both participants before a controller can move away from the observed frame.
            // This is the old published-index query, not a prediction of this epoch's movement.
            AsyncTickThreadMarker.runInAsyncTickContext {
                snapshot.forEach { controller ->
                    (controller.train as? CoreTrain)?.let(::detectSweptCollision)
                }
            }
            val batches = partition(snapshot, workerCount)
            val workerDurations = LongArray(batches.size)
            val controllerDurations = ConcurrentHashMap<String, Long>()
            val futures = ArrayList<Future<*>>(batches.size)

            batches.forEachIndexed { index, batch ->
                futures += workerPool.submit {
                    AsyncTickThreadMarker.runInAsyncTickContext {
                        val workerStart = nanoTime()
                        batch.forEach { controller ->
                            RailwaySimulationHalt.ensureRunning()
                            if (controller.profileId !in trainsPendingCollision &&
                                (controller.train as? CoreTrain)?.movementHeld != true) {
                                val controllerStart = nanoTime()
                                controller.tick(deltaMillis)
                                controllerDurations[controller.profileId] = nanoTime() - controllerStart
                            } else {
                                try {
                                    if (controller.profileId !in trainsPendingCollision &&
                                        (controller.train as? CoreTrain)?.canPrepareWhileFormationHeld == true) {
                                        controller.prepareWhileFormationHeld(deltaMillis)
                                    }
                                } finally {
                                    controller.freezeMotionForPhysicsEpoch()
                                }
                            }
                        }
                        workerDurations[index] = nanoTime() - workerStart
                    }
                }
            }
            awaitWorkers(futures)

            val coordinatorStart = nanoTime()
            // Publish only after every worker has finished reading the previous epoch.
            val publishedTrains = LinkedHashSet<CoreTrain>()
            snapshot.forEach { controller ->
                val published = attachedWorld.withRailTopologyReadLock {
                    synchronized(stateLock) {
                        if (RailwaySimulationHalt.isHalted || localStatus != PhysicsCoordinatorStatus.RUNNING || inFlight !== ticket) {
                            false
                        } else {
                            controller.publishPhysicsBuffers()
                            (controller.train as? CoreTrain)?.let(publishedTrains::add)
                            true
                        }
                    }
                }
                if (!published) return
            }
            // Collision decisions can mutate a stationary train without a controller. Its pending
            // state belongs to this epoch and must cross the same publication boundary.
            val framesPublished = attachedWorld.withRailTopologyReadLock {
                synchronized(stateLock) {
                    if (RailwaySimulationHalt.isHalted || localStatus != PhysicsCoordinatorStatus.RUNNING || inFlight !== ticket) {
                        false
                    } else {
                        attachedWorld.allTrains.filterIsInstance<CoreTrain>().forEach { train ->
                            // Detached trains publish stopped state rather than old controller velocity.
                            if (train.controller == null) train.clearActiveMotionFromPhysics()
                            if (publishedTrains.add(train)) train.publishPhysicsState()
                        }
                        publishedTrains.forEach { train ->
                            val previousFrame = train.publishedFrame
                            val frame = train.publishFrame(ticket.epoch)
                            if (!previousFrame.derailed && frame.derailed) {
                                train.queuePublishedDerailNotification()
                            }
                            if (shouldPublishMotionFrame(train, previousFrame)) {
                                attachedWorld.publishTrainFrame(frame)
                            }
                        }
                        true
                    }
                }
            }
            if (!framesPublished) return
            // Controllers observe this epoch's complete frame, never the previous epoch's head
            // combined with freshly flipped speed. Extension hooks remain outside Core locks.
            snapshot.forEach { controller ->
                if (RailwaySimulationHalt.isHalted || localStatus != PhysicsCoordinatorStatus.RUNNING) return
                controller.notifyPhysicsStatePublished()
            }
            if (RailwaySimulationHalt.isHalted || localStatus != PhysicsCoordinatorStatus.RUNNING) return
            runPublishedFrameProcessors(publishedTrains.toList(), ticket.epoch)
            val capturedWorldFrame = attachedWorld.withRailTopologyReadLock {
                synchronized(stateLock) {
                    if (RailwaySimulationHalt.isHalted || localStatus != PhysicsCoordinatorStatus.RUNNING || inFlight !== ticket) {
                        null
                    } else {
                        attachedWorld.rebuildSweptOccupancyIndex(ticket.epoch)
                        attachedWorld.captureWorldFrame(ticket.epoch)
                    }
                }
            } ?: return
            val occupancyStats = attachedWorld.sweptOccupancyStats()
            railwayEpochProcessors.toList().forEach { processor ->
                processor.process(capturedWorldFrame)
            }
            val coordinatorNanos = nanoTime() - coordinatorStart
            val wallNanos = nanoTime() - epochStartNanos
            val overloadCount = recordOverloadIfNeeded(wallNanos, epochStartNanos)
            val droppedDeltaMillis = rawDeltaMillis - deltaMillis
            val deltaClamped = droppedDeltaMillis > 0.0
            if (deltaClamped) {
                maybeLogClampedDelta(ticket.epoch, rawDeltaMillis, deltaMillis, epochStartNanos)
                clampedEpochCount++
            }
            val metrics = PhysicsEpochMetrics(
                epoch = ticket.epoch,
                source = source,
                deltaMillis = deltaMillis,
                rawDeltaMillis = rawDeltaMillis,
                droppedDeltaMillis = droppedDeltaMillis,
                deltaClamped = deltaClamped,
                clampedEpochCount = clampedEpochCount,
                wallNanos = wallNanos,
                workerMaxNanos = workerDurations.maxOrNull() ?: 0L,
                workerTotalNanos = workerDurations.sum(),
                coordinatorNanos = coordinatorNanos,
                activeTrainCount = snapshot.size,
                totalTrainCount = world?.allTrains?.size ?: 0,
                spanCount = occupancyStats.first,
                bucketReferenceCount = occupancyStats.second,
                candidateChecks = epochCandidateChecks.get(),
                collisionClaims = collisionClaims.size,
                overloadCountInWindow = overloadCount
            )
            if (overloadCount >= overloadsBeforeStall) {
                val failure = PhysicsOverloadException(
                    "Physics stalled after $overloadCount epochs reached " +
                        "${epochOverloadThresholdMillis}ms within ${overloadWindowMillis}ms"
                )
                val pending = synchronized(stateLock) {
                    if (RailwaySimulationHalt.isHalted || localStatus != PhysicsCoordinatorStatus.RUNNING || inFlight !== ticket) return
                    lastMetrics = metrics
                    lastControllerNanos = controllerDurations.toMap()
                    overloadRecoveryFrame = capturedWorldFrame
                    status = PhysicsCoordinatorStatus.STALLED
                    inFlight = null
                    ticket.completion.completeExceptionally(failure)
                    removePendingBetweenFramesLocked()
                }
                LOGGER.log(Level.SEVERE, failure.message, failure)
                failBetweenFrames(pending, failure)
                return
            }

            // Queue decisions from this frozen epoch before completing its ticket. The next
            // owner boundary can resolve them without running another physics epoch.
            applyPendingCollisionDecisions(ticket.epoch)

            // Topology transactions can stall physics while holding their write lock, so acquire
            // topology before stateLock. Close and exact-ticket publication then serialize here.
            val committed = attachedWorld.withRailTopologyReadLock {
                synchronized(stateLock) {
                    if (RailwaySimulationHalt.isHalted || localStatus != PhysicsCoordinatorStatus.RUNNING || inFlight !== ticket) {
                        return@withRailTopologyReadLock false
                    }
                    attachedWorld.publishCapturedWorldFrame(capturedWorldFrame)
                    lastMetrics = metrics
                    lastControllerNanos = controllerDurations.toMap()
                    latestCompleted = ticket
                    inFlight = null
                    ticket.completion.complete(metrics)
                    true
                }
            }
            if (committed) {
                AsyncTickThreadMarker.runInAsyncTickContext {
                    PhysicsRailEventBus.publish(PostTickEvent(attachedWorld, ticket.epoch, deltaMillis, capturedWorldFrame))
                }
            }
        } catch (t: Throwable) {
            val failure = if (t is PhysicsStalledException) t else {
                PhysicsStalledException("Physics epoch ${ticket.epoch} failed", t)
            }
            if (localStatus == PhysicsCoordinatorStatus.CLOSED) return
            val globalFailure = RailwaySimulationHalt.halt("Physics epoch ${ticket.epoch} failed", failure)
            settleHalt(globalFailure)
        }
    }

    private fun applyPendingCollisionDecisions(epoch: Long) {
        val attachedWorld = world ?: return
        val decisions = pendingCollisionDecisions.values
            .filter { it.detectedEpoch <= epoch }
            .sortedWith(compareBy({ it.pair.firstTrainId }, { it.pair.secondTrainId }))
        decisions.forEach { decision ->
            if (!pendingCollisionDecisions.remove(decision.pair, decision)) return@forEach
            val first = decision.firstTrain
            val second = decision.secondTrain
            submitBetweenFrames {
                try {
                    if (attachedWorld.getTrain(first.id) !== first || attachedWorld.getTrain(second.id) !== second) {
                        return@submitBetweenFrames
                    }
                    when (decision.resolution) {
                        CollisionResolution.DERAIL -> {
                            val reason = "collision at physics epoch $epoch between ${first.id} and ${second.id}"
                            first.derail(reason)
                            second.derail(reason)
                        }
                        CollisionResolution.LINK -> {
                            try {
                                first.linkFromCollisionOnOwner(second, decision.firstFrame, decision.secondFrame,
                                    decision.topologyRevision, decision.firstEnvelope, decision.secondEnvelope)
                            } catch (failure: CouplingRejectedException) {
                                RailwaySimulationHalt.ensureRunning()
                                val reason = "collision coupling failed between ${first.id} and ${second.id}: ${failure.message}"
                                LOGGER.log(Level.SEVERE, reason, failure)
                                if (attachedWorld.getTrain(first.id) === first) first.derail(reason)
                                if (attachedWorld.getTrain(second.id) === second) second.derail(reason)
                            }
                        }
                    }
                } catch (failure: Throwable) {
                    throw stall("Unexpected collision resolution failure: ${first.id}, ${second.id}", failure)
                } finally {
                    trainsPendingCollision.remove(first.id)
                    trainsPendingCollision.remove(second.id)
                }
            }
        }
    }

    private fun maybeLogClampedDelta(epoch: Long, rawDeltaMillis: Double, deltaMillis: Double, nowNanos: Long) {
        if (nowNanos < nextClampLogNanos) return
        nextClampLogNanos = nowNanos + TimeUnit.MILLISECONDS.toNanos(CLAMP_LOG_INTERVAL_MILLIS)
        LOGGER.warning(
            "Physics delta clamped in epoch $epoch: " +
                "raw=${rawDeltaMillis}ms applied=${deltaMillis}ms"
        )
    }

    private fun recordOverloadIfNeeded(wallNanos: Long, nowNanos: Long): Int {
        synchronized(stateLock) {
            val windowNanos = TimeUnit.MILLISECONDS.toNanos(overloadWindowMillis)
            while (overloadTimesNanos.isNotEmpty() && nowNanos - overloadTimesNanos.first() > windowNanos) {
                overloadTimesNanos.removeFirst()
            }
            if (wallNanos >= TimeUnit.MILLISECONDS.toNanos(epochOverloadThresholdMillis)) {
                overloadTimesNanos.addLast(nowNanos)
                LOGGER.warning(
                    "Physics epoch overload: ${wallNanos / 1_000_000.0}ms " +
                        "(${overloadTimesNanos.size}/$overloadsBeforeStall in ${overloadWindowMillis}ms)"
                )
            }
            return overloadTimesNanos.size
        }
    }

    private fun ensureUsableLocked() {
        RailwaySimulationHalt.ensureRunning()
        when (localStatus) {
            PhysicsCoordinatorStatus.RUNNING -> Unit
            PhysicsCoordinatorStatus.STALLED -> {
                if (overloadRecoveryFrame != null) throw PhysicsOverloadException("PhysicsCoordinator is stalled by overload")
                throw PhysicsStalledException("PhysicsCoordinator is stalled")
            }
            PhysicsCoordinatorStatus.CLOSED -> error("PhysicsCoordinator is closed")
        }
    }

    private fun failPendingResets(failure: Throwable) {
        val pending = synchronized(stateLock) { pendingResets.toList().also { pendingResets.clear() } }
        pending.forEach { it.completeExceptionally(failure) }
    }

    private fun removePendingBetweenFramesLocked(): List<BetweenFramesOperation<*>> =
        pendingBetweenFrames.toList().also {
            pendingBetweenFrames.clear()
            betweenFramesQueue.clear()
        }

    private fun failBetweenFrames(operations: List<BetweenFramesOperation<*>>, failure: Throwable) {
        operations.forEach { it.result.completeExceptionally(failure) }
    }

    /**
     * Stops new epochs and waits for the exact publication plus every owned executor.
     * The host must stop external producers first. Failure prevents final snapshot capture;
     * [close] remains nonblocking cancellation for existing callers.
     */
    fun stopAndAwait(timeoutMillis: Long = STOP_TIMEOUT_MILLIS) {
        require(timeoutMillis > 0) { "timeoutMillis must be > 0" }
        check(!ThreadGuard.isAsyncTickThread()) { "Physics shutdown must not wait on its own worker" }
        check(!Thread.holdsLock(stateLock)) { "Physics shutdown must run outside the state lock" }
        synchronized(stopLock) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            try {
                val ticket = beginShutdown()
                fallbackTimer.shutdownNow()
                ticket.completion.get(remainingNanos(deadline), TimeUnit.NANOSECONDS)
                coordinatorExecutor.shutdown()
                workerPool.shutdown()
                awaitTermination(coordinatorExecutor, deadline)
                awaitTermination(workerPool, deadline)
                awaitTermination(fallbackTimer, deadline)
                synchronized(stateLock) { ensureUsableLocked() }
            } catch (failure: Throwable) {
                val pending = synchronized(stateLock) {
                    acceptingWakes = false
                    if (localStatus != PhysicsCoordinatorStatus.CLOSED) status = PhysicsCoordinatorStatus.STALLED
                    inFlight?.completion?.completeExceptionally(failure)
                    inFlight = null
                    removePendingBetweenFramesLocked()
                }
                failPendingResets(failure)
                failBetweenFrames(pending, failure)
                fallbackTimer.shutdownNow()
                coordinatorExecutor.shutdownNow()
                workerPool.shutdownNow()
                listOf(coordinatorExecutor, workerPool, fallbackTimer).forEach { executor ->
                    try { awaitTermination(executor, deadline) }
                    catch (terminationFailure: Throwable) { failure.addSuppressed(terminationFailure) }
                }
                throw failure
            }
        }
    }

    private fun remainingNanos(deadline: Long): Long =
        (deadline - System.nanoTime()).coerceAtLeast(0L)

    private fun awaitTermination(executor: ExecutorService, deadline: Long) {
        if (!executor.awaitTermination(remainingNanos(deadline), TimeUnit.NANOSECONDS)) {
            throw TimeoutException("Physics executor did not terminate during shutdown")
        }
    }

    override fun close() {
        RailwaySimulationHalt.unregister(this)
        val pending = synchronized(stateLock) {
            if (localStatus == PhysicsCoordinatorStatus.CLOSED) return
            status = PhysicsCoordinatorStatus.CLOSED
            acceptingWakes = false
            inFlight?.completion?.completeExceptionally(
                PhysicsStalledException("PhysicsCoordinator closed while an epoch was running")
            )
            inFlight = null
            removePendingBetweenFramesLocked()
        }
        failPendingResets(IllegalStateException("PhysicsCoordinator is closed"))
        failBetweenFrames(pending, IllegalStateException("PhysicsCoordinator is closed"))
        fallbackTimer.shutdownNow()
        coordinatorExecutor.shutdownNow()
        workerPool.shutdownNow()
    }

    /**
     * Host teardown that also waits for every in-flight callback to leave this coordinator's pools.
     * [close] alone remains nonblocking cancellation. Interrupts are restored after actual exit;
     * an uncooperative callback retains its runtime resources and emits periodic wait diagnostics.
     * Never call from physics callbacks, completion callbacks holding the state lock, or while
     * holding resources needed by a callback. This does not wait for platform effect completion.
     */
    fun closeAndAwaitTermination() {
        check(!ThreadGuard.isAsyncTickThread()) { "Physics teardown must not wait on its own worker" }
        ThreadGuard.ensureMainThread("PhysicsCoordinator.closeAndAwaitTermination")
        check(!Thread.holdsLock(stateLock)) { "Physics teardown must run outside the state lock" }
        close()
        val owner = world?.runtimeId ?: "<unattached>"
        awaitExecutorTermination(coordinatorExecutor, "World $owner physics coordinator", LOGGER)
        awaitExecutorTermination(workerPool, "World $owner physics workers", LOGGER)
        awaitExecutorTermination(fallbackTimer, "World $owner physics fallback", LOGGER)
    }

    private fun completedBootstrapTicket(): PhysicsTicket {
        val metrics = PhysicsEpochMetrics(
            epoch = 0L,
            source = PhysicsWakeSource.MAIN_THREAD,
            deltaMillis = 0.0,
            rawDeltaMillis = 0.0,
            droppedDeltaMillis = 0.0,
            deltaClamped = false,
            clampedEpochCount = 0L,
            wallNanos = 0L,
            workerMaxNanos = 0L,
            workerTotalNanos = 0L,
            coordinatorNanos = 0L,
            activeTrainCount = 0,
            totalTrainCount = 0,
            spanCount = 0,
            bucketReferenceCount = 0,
            candidateChecks = 0L,
            collisionClaims = 0,
            overloadCountInWindow = 0
        )
        return PhysicsTicket(0L, CompletableFuture.completedFuture(metrics))
    }

    private fun awaitWorkers(futures: List<Future<*>>) {
        var failure: Throwable? = null
        futures.forEach { future ->
            try { future.get() } catch (error: java.util.concurrent.ExecutionException) {
                val cause = error.cause ?: error
                if (localStatus != PhysicsCoordinatorStatus.CLOSED) RailwaySimulationHalt.halt("Physics worker failed", cause)
                if (failure == null) failure = cause else if (failure !== cause) failure!!.addSuppressed(cause)
            }
        }
        failure?.let { throw it }
    }

    private fun <T> partition(values: List<T>, partitions: Int): List<List<T>> {
        if (values.isEmpty()) return emptyList()
        val count = minOf(partitions, values.size)
        val result = ArrayList<MutableList<T>>(count)
        repeat(count) { result.add(ArrayList()) }
        values.forEachIndexed { index, value -> result[index % count].add(value) }
        return result
    }

    companion object {
        const val BOOTSTRAP_DELTA_MILLIS = 50.0
        private val LOGGER = Logger.getLogger(PhysicsCoordinator::class.java.name)
        const val FALLBACK_PERIOD_MILLIS = 50L
        const val MAIN_HEARTBEAT_TIMEOUT_MILLIS = 75L
        const val DEFAULT_EPOCH_OVERLOAD_THRESHOLD_MILLIS = 50L
        const val DEFAULT_MAX_PHYSICS_DELTA_MILLIS = 200.0
        const val OVERLOAD_WINDOW_MILLIS = 10_000L
        const val OVERLOADS_BEFORE_STALL = 3
        private const val STOP_TIMEOUT_MILLIS = 10_000L
        private const val CLAMP_LOG_INTERVAL_MILLIS = 10_000L

        private val WORKER_SEQUENCE = AtomicLong()
        private val COORDINATOR_SEQUENCE = AtomicLong()
        private val TIMER_SEQUENCE = AtomicLong()
    }
}
