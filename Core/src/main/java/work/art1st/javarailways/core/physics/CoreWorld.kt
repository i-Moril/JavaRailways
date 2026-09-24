package work.art1st.javarailways.core.physics

import kotlinx.serialization.Serializable
import org.joml.Vector3dc
import work.art1st.javarailways.core.api.util.annotation.Heavy
import work.art1st.javarailways.core.api.event.DerailEvent
import work.art1st.javarailways.core.api.event.processor.MainRailEventBus
import work.art1st.javarailways.core.api.physics.rail.Rail
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.RailwayOccupancySnapshot
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.api.util.annotation.MainThread
import work.art1st.javarailways.core.api.util.plugin.Extendable
import work.art1st.javarailways.core.api.util.plugin.ExtensionDataContainer
import work.art1st.javarailways.core.api.util.tick.TickManager
import work.art1st.javarailways.core.control.signal.Signal
import work.art1st.javarailways.core.di.WorldParentContext
import work.art1st.javarailways.core.physics.train.CoreTrain
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.AtGradeRailCrossingIndex
import work.art1st.javarailways.core.physics.rail.RailView
import work.art1st.javarailways.core.physics.rail.RailSpatialIndex
import work.art1st.javarailways.core.physics.rail.SweptOccupancyQueryResult
import work.art1st.javarailways.core.physics.rail.topology.CoreTopologyInputAdapter
import work.art1st.javarailways.core.physics.rail.topology.TopologyStateBuilder
import work.art1st.javarailways.core.util.ThreadGuard
import work.art1st.javarailways.core.physics.train.SavedTrainV1
import work.art1st.javarailways.core.physics.tick.LatestTrainFrameMailbox
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 物理世界：由 [createEmpty] 或分离的 infrastructure/runtime 快照创建；Bukkit 等平台在创建后通过
 * [Extendable.registerExtensionData] 注册各插件的扩展数据。
 */
class CoreWorld(
    private val rails: MutableMap<UUID, CoreRail>,
    private val trains: MutableMap<String, CoreTrain>,
    private val signals: MutableMap<String, Signal>,
    override val tickManager: TickManager,
    val physicsCoordinator: PhysicsCoordinator,
    worldParent: WorldParentContext,
    private val extensionDataContainer: ExtensionDataContainer<Any> = ExtensionDataContainer()
) : World, Extendable<Any> by extensionDataContainer {
    private val railSpatialIndex = RailSpatialIndex()
    private val atGradeRailCrossingIndex = AtGradeRailCrossingIndex(railSpatialIndex)
    private val railTopologyLock = ReentrantReadWriteLock()
    private val railTopologyTransactionContext = ThreadLocal<RailTopologyTransactionContext?>()
    private val trainsByName = ConcurrentHashMap<String, CoreTrain>()
    val runtimeId: String = UUID.randomUUID().toString()

    private val publishedTrainFrames = LatestTrainFrameMailbox(DEFAULT_TRAIN_FRAME_MAILBOX_CAPACITY)
    @Volatile
    private var publishedWorldFrame: RailwayWorldFrameSnapshot =
        RailwayWorldFrameSnapshot.create(0L, 0L, emptyList())
    @Volatile
    private var sweptOccupancy: RailwayOccupancySnapshot = publishedWorldFrame.occupancy
    @Volatile
    private var railTopologyHealth: RailTopologyHealth = RailTopologyHealth.Healthy
    @Volatile
    private var publishedTopologyState: TopologyState = TopologyStateBuilder.build(
        CoreTopologyInputAdapter.capture(
            revision = 0L,
            sourceRails = rails.values,
            atGradeCrossings = atGradeRailCrossingIndex.snapshot()
        )
    )

    init {
        trains.values.forEach { train ->
            check(trainsByName.putIfAbsent(train.name, train) == null) {
                "duplicate train name: ${train.name}"
            }
        }
        worldParent.attachWorld(this)
        physicsCoordinator.attachWorld(this)
    }

    private constructor(
        tickManager: TickManager,
        physicsCoordinator: PhysicsCoordinator,
        worldParent: WorldParentContext
    ) : this(LinkedHashMap(), ConcurrentHashMap(), hashMapOf(), tickManager, physicsCoordinator, worldParent)

    companion object {
        const val INFRASTRUCTURE_SNAPSHOT_FORMAT_VERSION = 2
        const val RUNTIME_SNAPSHOT_FORMAT_VERSION = 1
        private const val DEFAULT_TRAIN_FRAME_MAILBOX_CAPACITY = 65_536

        fun createEmpty(
            tickManager: TickManager,
            worldParent: WorldParentContext,
            physicsCoordinator: PhysicsCoordinator = PhysicsCoordinator(1)
        ): CoreWorld = CoreWorld(tickManager, physicsCoordinator, worldParent)

        fun load(
            infrastructure: SavedInfrastructureV2,
            runtime: SavedRuntimeV1?,
            dependency: SnapshotDependency
        ): CoreWorld {
            require(infrastructure.formatVersion == INFRASTRUCTURE_SNAPSHOT_FORMAT_VERSION) {
                "Unsupported core infrastructure snapshot version: ${infrastructure.formatVersion}"
            }
            if (runtime != null) {
                require(runtime.formatVersion == RUNTIME_SNAPSHOT_FORMAT_VERSION) {
                    "Unsupported core runtime snapshot version: ${runtime.formatVersion}"
                }
                require(runtime.infrastructureRevision == infrastructure.infrastructureRevision) {
                    "runtime infrastructure revision ${runtime.infrastructureRevision} does not match " +
                        infrastructure.infrastructureRevision
                }
            }
            val world = CoreWorld(
                dependency.tickManager,
                dependency.physicsCoordinator,
                dependency.worldParent
            )
            world.railTopologyTransaction {
                val railDependency = CoreRail.SavedRailV1.Dependency(world)
                val loadedRails = infrastructure.rails.map { it.loadDisconnected(railDependency) }
                infrastructure.rails.zip(loadedRails).forEach { (savedRail, rail) ->
                    savedRail.restoreConnections(rail, railDependency)
                }
            }
            runtime?.trains.orEmpty().forEach { savedTrain ->
                val train = try {
                    savedTrain.load(world)
                } catch (failure: Exception) {
                    throw IllegalStateException(
                        "Failed to restore train '${savedTrain.name}' [${savedTrain.id}]",
                        failure
                    )
                }
                world.addTrain(train)
            }
            return world
        }
    }

    @MainThread
    internal fun addTrain(train: CoreTrain) {
        if (trains[train.id] === train && trainsByName[train.name] === train) return
        require(trains[train.name] == null && trainsByName[train.id] == null) {
            "train name and technical ID namespaces must not overlap: ${train.name}"
        }
        require(trainsByName.putIfAbsent(train.name, train).let { it == null || it === train }) {
            "duplicate train name in world: ${train.name}"
        }
        val previous = trains.putIfAbsent(train.id, train)
        if (previous != null && previous !== train) {
            trainsByName.remove(train.name, train)
            throw IllegalArgumentException("duplicate train id in world: ${train.id}")
        }
    }

    /** Player-facing lookup prefers the world-unique train name, then accepts the technical ID internally. */
    override fun getTrain(name: String): CoreTrain? = trainsByName[name] ?: trains[name]

    @MainThread
    fun renameTrain(train: CoreTrain, newName: String) {
        require(trains[train.id] === train) { "train is not registered in this world: ${train.id}" }
        if (newName == train.name) return
        require(trains[newName] == null) { "train name conflicts with a technical ID: $newName" }
        require(trainsByName.putIfAbsent(newName, train) == null) {
            "duplicate train name in world: $newName"
        }
        val oldName = train.name
        try {
            train.rename(newName)
            trainsByName.remove(oldName, train)
            publishTrainFrame(train.publishMetadataFrame())
        } catch (failure: Exception) {
            trainsByName.remove(newName, train)
            throw failure
        }
    }

    override val allTrains: Collection<CoreTrain>
        get() = trains.values

    /** Low-frequency maintenance boundary; callers must stop affected operations before awaiting. */
    @MainThread
    fun awaitCurrentPhysicsEpoch(): Long {
        val ticket = physicsCoordinator.captureCurrentTicket()
        ticket.await()
        return ticket.epoch
    }

    /** Publishes physics-origin derail transitions after their frames have crossed the epoch boundary. */
    @MainThread
    fun publishPendingDerailEvents() {
        trains.values.forEach { train ->
            val reason = train.pollDerailNotificationReason() ?: return@forEach
            MainRailEventBus.publish(DerailEvent(train, reason))
        }
    }

    fun occupyingTrainIds(railIds: Set<UUID>): Set<String> {
        if (railIds.isEmpty()) return emptySet()
        return trains.values.asSequence()
            .filter { train ->
                val occupiedRailIds = train.activePlacementEnvelope().mapTo(HashSet()) { it.railId }
                occupiedRailIds.any(railIds::contains)
            }
            .mapTo(linkedSetOf()) { it.id }
    }

    @MainThread
    override fun removeTrain(train: Train) {
        publishedTrainFrames.remove(train.id)
        trains.remove(train.id)
        trainsByName.remove(train.name, train)
    }

    /**
     * Creates and registers one rail as a structural topology edit.
     *
     * Curve construction must already be complete before this call. When invoked inside an existing
     * [railTopologyTransaction], registration joins that transaction; otherwise this method opens
     * one transaction and publishes one topology revision after registration succeeds.
     */
    @MainThread
    fun createRail(curveAB: PrimeCurve): CoreRail = railTopologyTransaction {
        createRailInTransaction(UUID.randomUUID(), curveAB)
    }

    /** Core-only primitive for persistent identity restore and atomic bulk topology operations. */
    @JvmSynthetic
    @MainThread
    internal fun createRailInTransaction(id: UUID, curveAB: PrimeCurve): CoreRail {
        check(railTopologyLock.isWriteLockedByCurrentThread) {
            "Rail creation must run inside a rail topology transaction"
        }
        require(id !in rails) { "duplicate rail id in world: $id" }
        val rail = CoreRail.createUnregistered(
            id = id,
            world = this,
            curveAB = curveAB
        )
        rails[id] = rail
        atGradeRailCrossingIndex.addOrReplace(rail)
        return rail
    }

    /**
     * Execute a validated infrastructure commit without exposing partially replaced rails to
     * world lookups. Every structural topology edit after world construction must enter this
     * boundary; changing only a turnout's active selection is runtime state, not a structural edit.
     * Expensive curve construction must finish before entering this boundary.
     */
    @MainThread
    fun <T> railTopologyTransaction(action: () -> T): T {
        val activeContext = railTopologyTransactionContext.get()
        if (activeContext != null) {
            return try {
                action()
            } catch (failure: Throwable) {
                activeContext.recordNestedFailure(failure)
                throw failure
            }
        }

        ensureTopologyHealthy()
        awaitCurrentPhysicsEpoch()
        return railTopologyLock.write {
            ensureTopologyHealthy()
            val context = RailTopologyTransactionContext(captureRailTopologyFingerprint())
            railTopologyTransactionContext.set(context)
            try {
                val result = action()
                context.nestedFailure?.let { nestedFailure ->
                    throw IllegalStateException(
                        "A nested rail topology transaction failed even though its caller caught the exception",
                        nestedFailure
                    )
                }
                if (captureRailTopologyFingerprint() != context.before) {
                    val nextRevision = Math.addExact(publishedTopologyState.revision, 1L)
                    val candidate = TopologyStateBuilder.build(
                        CoreTopologyInputAdapter.capture(
                            nextRevision,
                            rails.values,
                            atGradeRailCrossingIndex.snapshot()
                        )
                    )
                    publishedTopologyState = candidate
                }
                result
            } catch (failure: Throwable) {
                if (captureRailTopologyFingerprint() != context.before) {
                    railTopologyHealth = RailTopologyHealth.Faulted(
                        RailTopologyFault(
                            reason = RailTopologyFaultReason.TRANSACTION_FAILED_AFTER_STRUCTURAL_MUTATION,
                            lastPublishedRevision = publishedTopologyState.revision,
                            failureType = failure::class.qualifiedName ?: failure::class.simpleName ?: "unknown",
                            failureMessage = failure.message
                        )
                    )
                    physicsCoordinator.stall(
                        "Rail topology transaction failed after structural mutation; reload the world to recover",
                        failure
                    )
                }
                throw failure
            } finally {
                railTopologyTransactionContext.remove()
            }
        }
    }

    /**
     * Runtime-only structural topology revision, unrelated to persisted snapshot revisions.
     * The value advances only when a successful outer transaction publishes a changed graph.
     */
    val topologyRevision: Long
        get() = publishedTopologyState.revision

    override val topologyState: TopologyState
        get() = publishedTopologyState

    fun isRailTopologyTransactionActiveOnCurrentThread(): Boolean =
        railTopologyLock.isWriteLockedByCurrentThread

    val allRails: Collection<CoreRail>
        get() = railTopologyLock.read { rails.values.toList() }

    override fun getRail(id: UUID): CoreRail? = railTopologyLock.read { rails[id] }

    fun getRailsNear(position: Vector3dc, radius: Double): Collection<CoreRail> =
        railTopologyLock.read { railSpatialIndex.query(position, radius) }

    fun getRailsAlongRay(origin: Vector3dc, direction: Vector3dc, maxDistance: Double, radius: Double): Collection<CoreRail> =
        railTopologyLock.read { railSpatialIndex.queryAlongRay(origin, direction, maxDistance, radius) }

    @OptIn(Heavy::class)
    fun getNearestRail(position: Vector3dc): CoreRail? {
        ThreadGuard.ensureOffMainThread("CoreWorld.getNearestRail")
        return railTopologyLock.read {
            rails.values.minByOrNull { rail ->
                rail.curve.getNearestPoint(position).position.distance(position)
            }
        }
    }

    @MainThread
    override fun removeRail(rail: Rail) {
        if (railTopologyLock.isWriteLockedByCurrentThread) {
            removeRailInTransaction(rail)
        } else {
            railTopologyTransaction { removeRailInTransaction(rail) }
        }
    }

    private fun removeRailInTransaction(rail: Rail) {
        val removed = rail as? CoreRail
        if (removed != null) {
            val affectedRailIds = buildSet {
                add(removed.id)
                allRails.asSequence()
                    .filter { it !== removed }
                    .filter { candidate ->
                        candidate.viewAB.nextRails.any { it.rail === removed } ||
                            candidate.viewBA.nextRails.any { it.rail === removed }
                    }
                    .forEach { add(it.id) }
            }
            val occupyingTrainIds = occupyingTrainIds(affectedRailIds)
            require(occupyingTrainIds.isEmpty()) {
                "Cannot remove rail ${removed.id}; affected rails are occupied by ${occupyingTrainIds.sorted()}"
            }
            allRails.asSequence()
                .filter { it !== removed }
                .flatMap { sequenceOf(it.viewAB, it.viewBA) }
                .forEach { view ->
                    val removedWasActive = view.activeRail?.rail === removed
                    if (removedWasActive) view.activeRail = null
                    view.mutableNextRails.removeIf { it.rail === removed }
                    if (removedWasActive) view.activeRail = view.mutableNextRails.firstOrNull()
                }
            removed.viewAB.activeRail = null
            removed.viewBA.activeRail = null
            removed.viewAB.mutableNextRails.clear()
            removed.viewBA.mutableNextRails.clear()
            atGradeRailCrossingIndex.remove(removed.id)
            railSpatialIndex.removeRail(removed)
        }
        rails.remove(rail.id)
    }

    /** Rebuilt by the physics Coordinator after all train states are published. */
    internal fun rebuildSweptOccupancyIndex(physicsEpoch: Long) {
        sweptOccupancy = RailwayOccupancySnapshot.create(
            physicsEpoch,
            trains.values.map(CoreTrain::publishedFrame)
        )
    }

    /**
     * Publishes the coherent world frame after train frames and swept occupancy for [physicsEpoch].
     * Mismatched train epochs fail the physics ticket instead of exposing a mixed observation.
     */
    internal fun publishWorldFrame(physicsEpoch: Long): RailwayWorldFrameSnapshot {
        val frame = railTopologyLock.read {
            ensureTopologyHealthy()
            RailwayWorldFrameSnapshot.createWithOccupancy(
                physicsEpoch = physicsEpoch,
                topologyRevision = publishedTopologyState.revision,
                trains = trains.values.map(CoreTrain::publishedFrame),
                occupancy = sweptOccupancy
            )
        }
        publishedWorldFrame = frame
        return frame
    }

    private fun ensureTopologyHealthy() {
        when (val health = railTopologyHealth) {
            RailTopologyHealth.Healthy -> Unit
            is RailTopologyHealth.Faulted -> throw RailTopologyFaultedException(health.fault)
        }
    }

    private fun captureRailTopologyFingerprint(): RailTopologyFingerprint =
        RailTopologyFingerprint(
            rails.values
                .sortedBy { it.id.toString() }
                .map { rail ->
                    RailTopologyFingerprint.Entry(
                        rail = rail,
                        nextAtA = rail.viewBA.mutableNextRails.toList(),
                        nextAtB = rail.viewAB.mutableNextRails.toList()
                    )
                }
        )

    private data class RailTopologyFingerprint(val rails: List<Entry>) {
        data class Entry(
            val rail: CoreRail,
            val nextAtA: List<RailView>,
            val nextAtB: List<RailView>
        )
    }

    private class RailTopologyTransactionContext(val before: RailTopologyFingerprint) {
        var nestedFailure: Throwable? = null
            private set

        fun recordNestedFailure(failure: Throwable) {
            if (nestedFailure == null) nestedFailure = failure
        }
    }

    /** Latest complete immutable world frame. Initial value is epoch zero with no trains. */
    fun latestWorldFrame(): RailwayWorldFrameSnapshot = publishedWorldFrame

    internal fun sweptOccupancyStats(): Pair<Int, Int> =
        sweptOccupancy.spanCount to sweptOccupancy.bucketReferenceCount

    internal fun querySweptCollision(train: CoreTrain): SweptOccupancyQueryResult =
        sweptOccupancy.queryFirstCollision(
            trainId = train.id,
            ownSpans = train.publishedSweptSpans
        )

    /** Called by the Coordinator after atomically publishing an active train frame. */
    internal fun publishTrainFrame(frame: TrainFrameSnapshot) {
        publishedTrainFrames.offer(frame)
    }

    /** Enqueues the current immutable frame after an explicit command-side train commit. */
    fun publishCurrentTrainFrame(trainId: String): Boolean {
        val train = getTrain(trainId) ?: return false
        publishTrainFrame(train.publishedFrame)
        return true
    }

    /**
     * Drains at most one latest frame per train without executing render or platform side effects.
     * A slow consumer therefore cannot build an unbounded per-tick backlog.
     */
    fun drainPublishedTrainFrames(limit: Int = Int.MAX_VALUE): List<TrainFrameSnapshot> =
        publishedTrainFrames.drain(limit)

    fun trainFrameMailboxMetrics(): LatestTrainFrameMailbox.Metrics = publishedTrainFrames.metrics()

    fun saveInfrastructure(infrastructureRevision: Long): SavedInfrastructureV2 {
        require(infrastructureRevision >= 0L)
        return SavedInfrastructureV2(
            formatVersion = INFRASTRUCTURE_SNAPSHOT_FORMAT_VERSION,
            infrastructureRevision = infrastructureRevision,
            rails = allRails.map { it.save() }
        )
    }

    fun saveRuntime(infrastructureRevision: Long, physicsEpoch: Long): SavedRuntimeV1 {
        require(infrastructureRevision >= 0L && physicsEpoch >= 0L)
        return SavedRuntimeV1(
            formatVersion = RUNTIME_SNAPSHOT_FORMAT_VERSION,
            infrastructureRevision = infrastructureRevision,
            physicsEpoch = physicsEpoch,
            trains = trains.values.map { it.save() }
        )
    }

    @Serializable
    data class SavedInfrastructureV2(
        val formatVersion: Int,
        val infrastructureRevision: Long,
        val rails: List<CoreRail.SavedRailV1>
    )

    @Serializable
    data class SavedRuntimeV1(
        val formatVersion: Int,
        val infrastructureRevision: Long,
        val physicsEpoch: Long,
        val trains: List<SavedTrainV1>
    )

    data class SnapshotDependency(
        val tickManager: TickManager,
        val worldParent: WorldParentContext,
        val physicsCoordinator: PhysicsCoordinator = PhysicsCoordinator(1)
    )
}
