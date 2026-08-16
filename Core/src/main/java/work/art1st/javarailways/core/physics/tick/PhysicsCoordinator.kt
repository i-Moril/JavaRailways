package work.art1st.javarailways.core.physics.tick

import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.api.event.CollisionEvent
import work.art1st.javarailways.core.api.event.CollisionResolution
import work.art1st.javarailways.core.api.event.processor.PhysicsRailEventBus
import work.art1st.javarailways.core.api.util.annotation.AsyncEventApi
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.train.CoreTrain
import work.art1st.javarailways.core.util.AsyncTickThreadMarker
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

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
    val resolution: CollisionResolution
)

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

class PhysicsStalledException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

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
    private val automaticFallback: Boolean = true,
    private val publishCollisionEvents: Boolean = true
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

    private val stateLock = Any()
    private val controllers = ConcurrentHashMap<String, TrainController>()
    private val publishedFrameProcessors = CopyOnWriteArrayList<PublishedTrainFrameProcessor>()
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
    var status: PhysicsCoordinatorStatus = PhysicsCoordinatorStatus.RUNNING
        private set

    @Volatile
    private var lastMetrics: PhysicsEpochMetrics? = null

    @Volatile
    private var lastControllerNanos: Map<String, Long> = emptyMap()

    private var started = false
    private var nextEpoch = 1L
    private var previousPhysicsStartNanos = 0L
    private var lastMainWakeNanos = nanoTime()
    private var inFlight: PhysicsTicket? = null
    private var latestCompleted: PhysicsTicket = completedBootstrapTicket()

    fun attachWorld(world: CoreWorld) {
        synchronized(stateLock) {
            check(this.world == null) { "PhysicsCoordinator is already attached to a world" }
            this.world = world
        }
    }

    fun start() {
        synchronized(stateLock) {
            check(status != PhysicsCoordinatorStatus.CLOSED) { "PhysicsCoordinator is closed" }
            if (started) return
            check(world != null) { "PhysicsCoordinator must be attached to a world before start" }
            world!!.rebuildSweptOccupancyIndex()
            started = true
        }
        if (automaticFallback) {
            fallbackTimer.scheduleAtFixedRate(
                ::timerWake,
                fallbackPeriodMillis,
                fallbackPeriodMillis,
                TimeUnit.MILLISECONDS
            )
        }
    }

    fun register(controller: TrainController) {
        val previous = controllers.putIfAbsent(controller.profileId, controller)
        check(previous == null || previous === controller) {
            "A different train controller is already registered for '${controller.profileId}'"
        }
    }

    fun unregister(controller: TrainController) {
        controllers.remove(controller.profileId, controller)
        trainsPublishingMotionFrames.remove(controller.profileId)
    }

    fun registerPublishedFrameProcessor(processor: PublishedTrainFrameProcessor) {
        publishedFrameProcessors.addIfAbsent(processor)
    }

    fun unregisterPublishedFrameProcessor(processor: PublishedTrainFrameProcessor) {
        publishedFrameProcessors.remove(processor)
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

    fun latestMetrics(): PhysicsEpochMetrics? = lastMetrics

    fun latestControllerDurationsNanos(): Map<String, Long> = lastControllerNanos

    /** Called by a train worker before movement; collision resolution is applied next epoch. */
    @OptIn(AsyncEventApi::class)
    internal fun detectSweptCollision(train: CoreTrain): Boolean {
        if (train.id in trainsPendingCollision) return true
        val attachedWorld = world ?: return false
        val result = attachedWorld.querySweptCollision(train)
        epochCandidateChecks.addAndGet(result.checkedCandidates.toLong())
        if (result.candidateLimitReached) {
            System.err.println("[JavaRailways] Collision candidate limit reached for train '${train.id}' in epoch $runningEpoch")
        }
        val otherId = result.collidingTrainId ?: return false
        val other = attachedWorld.getTrain(otherId) ?: return false
        val pair = CollisionPair.of(train.id, other.id)
        trainsPendingCollision.add(pair.firstTrainId)
        trainsPendingCollision.add(pair.secondTrainId)

        if (collisionClaims.add(pair)) {
            val event = CollisionEvent(
                epoch = runningEpoch,
                worldId = attachedWorld.runtimeId,
                trainAId = pair.firstTrainId,
                trainBId = pair.secondTrainId,
                relativeSpeed = relativeSpeed(train, other)
            )
            if (publishCollisionEvents) PhysicsRailEventBus.publish(event)
            pendingCollisionDecisions[pair] = PendingCollisionDecision(runningEpoch, pair, event.resolution)
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
        futures.forEach(Future<*>::get)
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
            previousFrame.derailed != train.isDerailed ||
            previousFrame.reversed != train.isReversed
    }

    private fun relativeSpeed(first: CoreTrain, second: CoreTrain): Double {
        val firstVelocity = org.joml.Vector3d(first.headLocation.tangent).mul(first.speed)
        val secondVelocity = org.joml.Vector3d(second.headLocation.tangent).mul(second.speed)
        return firstVelocity.sub(secondVelocity).length()
    }

    private fun timerWake() {
        val shouldWake = synchronized(stateLock) {
            if (!started || status != PhysicsCoordinatorStatus.RUNNING) {
                false
            } else {
                nanoTime() - lastMainWakeNanos >= TimeUnit.MILLISECONDS.toNanos(mainHeartbeatTimeoutMillis)
            }
        }
        if (shouldWake) {
            requestStep(PhysicsWakeSource.TIMER_FALLBACK)
        }
    }

    private fun requestStep(source: PhysicsWakeSource, fixedDeltaMillis: Double? = null): PhysicsTicket {
        val ticket: PhysicsTicket
        val startNanos: Long
        val rawDeltaMillis: Double
        val deltaMillis: Double
        synchronized(stateLock) {
            ensureUsableLocked()
            inFlight?.let { return it }
            check(started) { "PhysicsCoordinator has not been started" }

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
        }

        coordinatorExecutor.execute {
            runEpoch(ticket, source, startNanos, rawDeltaMillis, deltaMillis)
        }
        return ticket
    }

    private fun runEpoch(
        ticket: PhysicsTicket,
        source: PhysicsWakeSource,
        epochStartNanos: Long,
        rawDeltaMillis: Double,
        deltaMillis: Double
    ) {
        try {
            runningEpoch = ticket.epoch
            collisionClaims.clear()
            epochCandidateChecks.set(0L)
            applyPendingCollisionDecisions(ticket.epoch)
            val snapshot = controllers.values.sortedBy { it.profileId }
            val batches = partition(snapshot, workerCount)
            val workerDurations = LongArray(batches.size)
            val controllerDurations = ConcurrentHashMap<String, Long>()
            val futures = ArrayList<Future<*>>(batches.size)

            batches.forEachIndexed { index, batch ->
                futures += workerPool.submit {
                    AsyncTickThreadMarker.runInAsyncTickContext {
                        val workerStart = nanoTime()
                        batch.forEach { controller ->
                            if (controller.profileId !in trainsPendingCollision) {
                                val controllerStart = nanoTime()
                                if (controller.preparePhysicsTick()) {
                                    controller.tick(deltaMillis)
                                }
                                controllerDurations[controller.profileId] = nanoTime() - controllerStart
                            }
                        }
                        workerDurations[index] = nanoTime() - workerStart
                    }
                }
            }
            futures.forEach { it.get() }

            val coordinatorStart = nanoTime()
            // Publish only after every worker has finished reading the previous epoch.
            val publishedTrains = LinkedHashSet<CoreTrain>()
            snapshot.forEach { controller ->
                controller.publishPhysicsState()
                (controller.train as? CoreTrain)?.let(publishedTrains::add)
            }
            // Collision decisions can mutate a stationary train without a controller. Its pending
            // state belongs to this epoch and must cross the same publication boundary.
            world?.allTrains?.filterIsInstance<CoreTrain>()?.forEach { train ->
                if (publishedTrains.add(train)) train.publishPhysicsState()
            }
            publishedTrains.forEach { train ->
                val previousFrame = train.publishedFrame
                val frame = train.publishFrame(ticket.epoch)
                if (!previousFrame.derailed && frame.derailed) {
                    train.queuePublishedDerailNotification()
                }
                if (shouldPublishMotionFrame(train, previousFrame)) {
                    world?.publishTrainFrame(frame)
                }
            }
            runPublishedFrameProcessors(publishedTrains.toList(), ticket.epoch)
            world?.rebuildSweptOccupancyIndex()
            val occupancyStats = world?.sweptOccupancyStats() ?: (0 to 0)
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
            lastMetrics = metrics
            lastControllerNanos = controllerDurations.toMap()

            if (overloadCount >= overloadsBeforeStall) {
                val failure = PhysicsStalledException(
                    "Physics stalled after $overloadCount epochs reached " +
                        "${epochOverloadThresholdMillis}ms within ${overloadWindowMillis}ms"
                )
                synchronized(stateLock) {
                    status = PhysicsCoordinatorStatus.STALLED
                    inFlight = null
                }
                ticket.completion.completeExceptionally(failure)
                return
            }

            synchronized(stateLock) {
                latestCompleted = ticket
                inFlight = null
            }
            ticket.completion.complete(metrics)
        } catch (t: Throwable) {
            val failure = if (t is PhysicsStalledException) t else {
                PhysicsStalledException("Physics epoch ${ticket.epoch} failed", t)
            }
            synchronized(stateLock) {
                status = PhysicsCoordinatorStatus.STALLED
                inFlight = null
            }
            ticket.completion.completeExceptionally(failure)
        }
    }

    private fun applyPendingCollisionDecisions(epoch: Long) {
        val attachedWorld = world ?: return
        val decisions = pendingCollisionDecisions.values
            .filter { it.detectedEpoch < epoch }
            .sortedWith(compareBy({ it.pair.firstTrainId }, { it.pair.secondTrainId }))
        decisions.forEach { decision ->
            if (!pendingCollisionDecisions.remove(decision.pair, decision)) return@forEach
            val first = attachedWorld.getTrain(decision.pair.firstTrainId) ?: return@forEach
            val second = attachedWorld.getTrain(decision.pair.secondTrainId) ?: return@forEach
            when (decision.resolution) {
                CollisionResolution.DERAIL -> {
                    val reason = "collision at physics epoch $epoch between ${first.id} and ${second.id}"
                    first.markDerailedFromPhysics(reason)
                    second.markDerailedFromPhysics(reason)
                }
                CollisionResolution.LINK -> {
                    val linkOwner = if (first.controller != null) first else second
                    val linkedTrain = if (linkOwner === first) second else first
                    linkOwner.linkWith(linkedTrain)
                    // linkWith is an exceptional main-thread structural transaction. Keep both
                    // trains frozen until that transaction has run, then release the surviving ID.
                    attachedWorld.tickManager.scheduleMainThreadPostTickTask {
                        trainsPendingCollision.remove(decision.pair.firstTrainId)
                        trainsPendingCollision.remove(decision.pair.secondTrainId)
                    }
                    return@forEach
                }
            }
            trainsPendingCollision.remove(decision.pair.firstTrainId)
            trainsPendingCollision.remove(decision.pair.secondTrainId)
        }
    }

    private fun maybeLogClampedDelta(epoch: Long, rawDeltaMillis: Double, deltaMillis: Double, nowNanos: Long) {
        if (nowNanos < nextClampLogNanos) return
        nextClampLogNanos = nowNanos + TimeUnit.MILLISECONDS.toNanos(CLAMP_LOG_INTERVAL_MILLIS)
        System.err.println(
            "[JavaRailways] Physics delta clamped in epoch $epoch: " +
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
                System.err.println(
                    "[JavaRailways] Physics epoch overload: ${wallNanos / 1_000_000.0}ms " +
                        "(${overloadTimesNanos.size}/$overloadsBeforeStall in ${overloadWindowMillis}ms)"
                )
            }
            return overloadTimesNanos.size
        }
    }

    private fun ensureUsableLocked() {
        when (status) {
            PhysicsCoordinatorStatus.RUNNING -> Unit
            PhysicsCoordinatorStatus.STALLED -> throw PhysicsStalledException("PhysicsCoordinator is stalled")
            PhysicsCoordinatorStatus.CLOSED -> error("PhysicsCoordinator is closed")
        }
    }

    override fun close() {
        synchronized(stateLock) {
            if (status == PhysicsCoordinatorStatus.CLOSED) return
            status = PhysicsCoordinatorStatus.CLOSED
            inFlight?.completion?.completeExceptionally(
                PhysicsStalledException("PhysicsCoordinator closed while an epoch was running")
            )
            inFlight = null
        }
        fallbackTimer.shutdownNow()
        coordinatorExecutor.shutdownNow()
        workerPool.shutdownNow()
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
        const val FALLBACK_PERIOD_MILLIS = 50L
        const val MAIN_HEARTBEAT_TIMEOUT_MILLIS = 75L
        const val DEFAULT_EPOCH_OVERLOAD_THRESHOLD_MILLIS = 50L
        const val DEFAULT_MAX_PHYSICS_DELTA_MILLIS = 200.0
        const val OVERLOAD_WINDOW_MILLIS = 10_000L
        const val OVERLOADS_BEFORE_STALL = 3
        private const val CLAMP_LOG_INTERVAL_MILLIS = 10_000L

        private val WORKER_SEQUENCE = AtomicLong()
        private val COORDINATOR_SEQUENCE = AtomicLong()
        private val TIMER_SEQUENCE = AtomicLong()
    }
}
