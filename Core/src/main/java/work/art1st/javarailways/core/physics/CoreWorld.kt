package work.art1st.javarailways.core.physics

import kotlinx.serialization.Serializable
import org.joml.Vector3dc
import work.art1st.javarailways.core.api.util.annotation.Heavy
import work.art1st.javarailways.core.api.event.DerailEvent
import work.art1st.javarailways.core.api.event.processor.MainRailEventBus
import work.art1st.javarailways.core.api.physics.rail.Rail
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.AtGradeRailCrossing
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNode
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
import work.art1st.javarailways.core.physics.rail.PreparedRailCreation
import work.art1st.javarailways.core.physics.rail.PreparedRailCreationBatch
import work.art1st.javarailways.core.physics.rail.PreparedRailCreationRejectedException
import work.art1st.javarailways.core.physics.rail.PreparedRailCreationRejectionReason
import work.art1st.javarailways.core.physics.rail.PreparedRailConnectionPlan
import work.art1st.javarailways.core.physics.rail.PreparedRailTopologyUpdate
import work.art1st.javarailways.core.physics.rail.PreparedRailWorldSnapshotPlan
import work.art1st.javarailways.core.physics.rail.topology.CoreTopologyInputAdapter
import work.art1st.javarailways.core.physics.rail.topology.TopologyInput
import work.art1st.javarailways.core.physics.rail.topology.TopologyInputRail
import work.art1st.javarailways.core.physics.rail.topology.TopologyStateBuilder
import work.art1st.javarailways.core.api.threading.ThreadGuard
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
    private val activeRailSelectionLock = Any()
    private val railTopologyTransactionContext = ThreadLocal<RailTopologyTransactionContext?>()
    private val topologyListeners = java.util.concurrent.CopyOnWriteArrayList<
        work.art1st.javarailways.core.api.physics.rail.topology.RailTopologyChangeListener>()
    private val trainsByName = ConcurrentHashMap<String, CoreTrain>()
    val runtimeId: String = UUID.randomUUID().toString()

    private val publishedTrainFrames = LatestTrainFrameMailbox(DEFAULT_TRAIN_FRAME_MAILBOX_CAPACITY)
    private var activeRailSelectionRevision = 0L
    @Volatile
    private var cachedActiveContinuations = ActiveRailContinuationsSnapshot.empty(0L)
    @Volatile
    private var publishedWorldFrame: RailwayWorldFrameSnapshot =
        RailwayWorldFrameSnapshot.create(0L, 0L, emptyList(), cachedActiveContinuations)
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
    internal fun validateNewTrainIdentity(id: String, name: String) {
        require(trains[name] == null && trainsByName[id] == null) {
            "train name and technical ID namespaces must not overlap: $name"
        }
        require(trainsByName[name] == null) { "duplicate train name in world: $name" }
        require(trains[id] == null) { "duplicate train id in world: $id" }
    }

    @MainThread
    internal fun addTrain(train: CoreTrain) {
        if (trains[train.id] === train && trainsByName[train.name] === train) return
        validateNewTrainIdentity(train.id, train.name)
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
     * Performs value-only geometry sampling, briefly captures spatial candidates from one topology
     * root, and then runs exact at-grade-crossing detection without holding the topology lock.
     * Scheduling belongs to the platform adapter; Core creates no worker or future.
     *
     * [excludedExistingRailIds] names rails that the same outer install transaction will replace.
     * They are omitted from new-vs-existing detection and must still exist when installation starts.
     */
    @OptIn(Heavy::class)
    fun prepareRailCreationBatch(
        curvesInOrder: List<PrimeCurve>,
        excludedExistingRailIds: Set<UUID> = emptySet()
    ): PreparedRailCreationBatch = prepareRailCreationBatchWithIds(
        curvesInOrder.map { curve -> PreparedRailCreation(UUID.randomUUID(), curve) },
        excludedExistingRailIds
    )

    /** Internal stable-identity variant used only by persisted snapshot restoration. */
    @OptIn(Heavy::class)
    private fun prepareRailCreationBatchWithIds(
        creationsInOrder: List<PreparedRailCreation>,
        excludedExistingRailIds: Set<UUID>
    ): PreparedRailCreationBatch {
        ThreadGuard.ensureOffMainThread("CoreWorld.prepareRailCreationBatch")
        require(creationsInOrder.isNotEmpty()) { "rail creation batch must not be empty" }
        require(creationsInOrder.map(PreparedRailCreation::railId).distinct().size == creationsInOrder.size) {
            "rail creation IDs must be unique"
        }
        val sampledRails = creationsInOrder.map { creation ->
            atGradeRailCrossingIndex.prepareGeometry(creation.railId, creation.curve)
        }
        val captured = railTopologyLock.read {
            ensureTopologyHealthy()
            require(excludedExistingRailIds.all(rails::containsKey)) {
                "excluded rails must exist in the captured world topology"
            }
            CapturedRailCreationCandidates(
                topologyRoot = publishedTopologyState,
                candidates = atGradeRailCrossingIndex.captureCandidateSnapshot(
                    sampledRails,
                    excludedExistingRailIds
                )
            )
        }
        val crossings = atGradeRailCrossingIndex.detectPreparedCrossings(
            sampledRails,
            captured.candidates
        )
        return PreparedRailCreationBatch.create(
            ownerWorld = this,
            topologyRoot = captured.topologyRoot,
            creations = creationsInOrder,
            excludedExistingRailIds = excludedExistingRailIds,
            sampledRails = sampledRails,
            crossings = crossings
        )
    }

    /**
     * Prepares the exact structural portion of one persisted whole-world snapshot restore. Core owns
     * the complete physical diff and derives endpoint changes after decoding the target snapshot
     * against its target geometry.
     */
    @JvmSynthetic
    @OptIn(Heavy::class)
    fun prepareRailWorldSnapshotRestore(
        currentSnapshot: List<CoreRail.SavedRailV1>,
        targetSnapshot: List<CoreRail.SavedRailV1>
    ): PreparedRailWorldSnapshotPlan = prepareRailWorldSnapshotRestore(
        currentSnapshot,
        targetSnapshot,
        preserveCurrentActiveSelections = false
    )

    /** Structural restore variant that retains any still-valid live branch selection. */
    @JvmSynthetic
    @OptIn(Heavy::class)
    fun prepareRailWorldSnapshotRestorePreservingActiveSelections(
        currentSnapshot: List<CoreRail.SavedRailV1>,
        targetSnapshot: List<CoreRail.SavedRailV1>
    ): PreparedRailWorldSnapshotPlan = prepareRailWorldSnapshotRestore(
        currentSnapshot,
        targetSnapshot,
        preserveCurrentActiveSelections = true
    )

    private fun prepareRailWorldSnapshotRestore(
        currentSnapshot: List<CoreRail.SavedRailV1>,
        targetSnapshot: List<CoreRail.SavedRailV1>,
        preserveCurrentActiveSelections: Boolean
    ): PreparedRailWorldSnapshotPlan {
        ThreadGuard.ensureOffMainThread("CoreWorld.prepareRailWorldSnapshotRestore")
        fun index(snapshot: List<CoreRail.SavedRailV1>): Map<UUID, CoreRail.SavedRailV1> {
            val indexed = snapshot.associateBy { UUID.fromString(it.id) }
            require(indexed.size == snapshot.size) { "rail snapshot contains duplicate IDs" }
            return indexed
        }
        val currentSaved = index(currentSnapshot)
        val targetSaved = index(targetSnapshot)
        val removedOrReplacedRailIds = currentSaved.keys.filterTo(linkedSetOf()) { railId ->
            val target = targetSaved[railId]
            target == null || currentSaved.getValue(railId).curveAB != target.curveAB
        }
        val createdOrReplacedRailIds = targetSaved.keys.filterTo(linkedSetOf()) { railId ->
            val current = currentSaved[railId]
            current == null || current.curveAB != targetSaved.getValue(railId).curveAB
        }
        val targetCreations = createdOrReplacedRailIds.map { railId ->
            val saved = requireNotNull(targetSaved[railId]) { "missing target rail $railId" }
            PreparedRailCreation(railId, saved.curveAB.load(Unit))
        }
        val prepared = targetCreations.takeIf(List<PreparedRailCreation>::isNotEmpty)?.let { creations ->
            prepareRailCreationBatchWithIds(creations, removedOrReplacedRailIds)
        }
        val captured = railTopologyLock.read {
            ensureTopologyHealthy()
            val liveById = rails.toMap()
            require(liveById.keys == currentSaved.keys &&
                liveById.all { (railId, rail) ->
                    rail.curve.save() == currentSaved.getValue(railId).curveAB
                }
            ) { "current rail snapshot changed during restore preparation" }
            val root = publishedTopologyState
            require(prepared == null || prepared.topologyRoot === root) {
                "rail topology changed during restore preparation"
            }
            Triple(root, liveById, atGradeRailCrossingIndex.snapshot())
        }
        return PreparedRailWorldSnapshotPlan.create(
            world = this,
            topologyRoot = captured.first,
            currentById = captured.second,
            currentSaved = currentSaved,
            targetSaved = targetSaved,
            removedOrReplacedRailIds = removedOrReplacedRailIds,
            createdOrReplacedRailIds = createdOrReplacedRailIds,
            preparedCreations = prepared,
            rawCurrentCrossings = captured.third,
            preserveCurrentActiveSelections = preserveCurrentActiveSelections
        )
    }

    /** Builds the normalized target for one already captured explicit connection plan. */
    @JvmSynthetic
    @OptIn(Heavy::class)
    fun prepareRailTopologyUpdate(plan: PreparedRailConnectionPlan): PreparedRailTopologyUpdate {
        ThreadGuard.ensureOffMainThread("CoreWorld.prepareRailTopologyUpdate")
        val batch = plan.preparedBatch()
        val orderedFinalConnections = plan.finalConnections()
        val rawCrossings = railTopologyLock.read {
            ensureTopologyHealthy()
            plan.requireCurrent(this)
            check(publishedTopologyState === plan.topologyRoot) {
                "Prepared explicit connection plan is stale; rebuild it from the current topology"
            }
            atGradeRailCrossingIndex.snapshot()
        }
        val baseInput = CoreTopologyInputAdapter.capture(plan.topologyRoot)
        val removedIds = batch.excludedExistingRailIds
        val createdCurves = batch.creations.associate { it.railId to it.curve }
        return buildPreparedTopologyUpdate(
            baseRoot = plan.topologyRoot,
            baseInput = baseInput,
            removedRailIds = removedIds,
            addedRailCurves = createdCurves,
            finalConnections = orderedFinalConnections.associate { connection ->
                connection.endpoint to connection.connectedEndpoints.toSet()
            },
            finalConnectionOrder = orderedFinalConnections.associate { connection ->
                connection.endpoint to connection.connectedEndpoints
            },
            finalActiveEndpoints = orderedFinalConnections.associate { connection ->
                connection.endpoint to connection.activeEndpoint
            },
            rawBaseCrossings = rawCrossings,
            addedCrossings = batch.crossings
        )
    }

    /** Captures one explicit endpoint plan under one coherent topology read lock. */
    @JvmSynthetic
    fun capturePreparedRailConnectionPlan(
        preparedBatch: PreparedRailCreationBatch,
        finalConnections: Collection<work.art1st.javarailways.core.physics.rail.PreparedRailEndpointConnection>
    ): PreparedRailConnectionPlan = railTopologyLock.read {
        ensureTopologyHealthy()
        PreparedRailConnectionPlan.captureLocked(this, preparedBatch, finalConnections)
    }

    /** Derives replacement and added endpoint states under one coherent topology read lock. */
    @JvmSynthetic
    fun capturePreparedRailConnectionPlan(
        preparedBatch: PreparedRailCreationBatch,
        replacementEndpoints: Map<work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef,
            work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef>,
        addedConnections: Collection<Pair<
            work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef,
            work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef>>
    ): PreparedRailConnectionPlan = railTopologyLock.read {
        ensureTopologyHealthy()
        PreparedRailConnectionPlan.captureExplicitLocked(
            this,
            preparedBatch,
            replacementEndpoints,
            addedConnections
        )
    }

    /** Builds the normalized target for a removal-only online edit. */
    @JvmSynthetic
    @OptIn(Heavy::class)
    fun prepareRailRemovalTopologyUpdate(
        baseRoot: TopologyState,
        removedRailIds: Set<UUID>
    ): PreparedRailTopologyUpdate {
        ThreadGuard.ensureOffMainThread("CoreWorld.prepareRailRemovalTopologyUpdate")
        require(removedRailIds.isNotEmpty()) { "a prepared removal must name at least one rail" }
        val rawCrossings = railTopologyLock.read {
            ensureTopologyHealthy()
            check(publishedTopologyState === baseRoot) {
                "Rail removal preparation is stale; rebuild it from the current topology"
            }
            require(removedRailIds.all(rails::containsKey)) { "prepared removal references a missing rail" }
            atGradeRailCrossingIndex.snapshot()
        }
        val baseInput = CoreTopologyInputAdapter.capture(baseRoot)
        return buildPreparedTopologyUpdate(
            baseRoot = baseRoot,
            baseInput = baseInput,
            removedRailIds = removedRailIds,
            addedRailCurves = emptyMap(),
            finalConnections = emptyMap(),
            finalConnectionOrder = emptyMap(),
            finalActiveEndpoints = emptyMap(),
            rawBaseCrossings = rawCrossings,
            addedCrossings = emptyList()
        )
    }

    private fun buildPreparedTopologyUpdate(
        baseRoot: TopologyState,
        baseInput: TopologyInput,
        removedRailIds: Set<UUID>,
        addedRailCurves: Map<UUID, PrimeCurve>,
        finalConnections: Map<work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef,
            Set<work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef>>,
        finalConnectionOrder: Map<work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef,
            List<work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef>>,
        finalActiveEndpoints: Map<work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef,
            work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef?>,
        rawBaseCrossings: List<work.art1st.javarailways.core.api.physics.rail.topology.AtGradeRailCrossing>,
        addedCrossings: List<work.art1st.javarailways.core.api.physics.rail.topology.AtGradeRailCrossing>
    ): PreparedRailTopologyUpdate {
        check(baseInput.revision == baseRoot.revision)
        require(addedRailCurves.keys.intersect(removedRailIds).isEmpty())
        require(finalActiveEndpoints.keys == finalConnections.keys)
        require(finalConnectionOrder.keys == finalConnections.keys)
        val targetRailIds = (baseInput.rails.mapTo(linkedSetOf(), TopologyInputRail::id) - removedRailIds) +
            addedRailCurves.keys
        val continuations = linkedMapOf<work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef,
            MutableSet<work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef>>()
        baseInput.continuations.forEach { (endpoint, targets) ->
            if (endpoint.railId !in removedRailIds) {
                continuations[endpoint] = targets.filterTo(linkedSetOf()) { it.railId !in removedRailIds }
            }
        }
        finalConnections.forEach { (endpoint, targets) ->
            require(endpoint.railId in targetRailIds && targets.all { it.railId in targetRailIds }) {
                "prepared connection references a rail outside the target inventory"
            }
            continuations[endpoint] = targets.toMutableSet()
        }
        val nextRevision = Math.addExact(baseRoot.revision, 1L)
        val targetInput = TopologyInput(
            revision = nextRevision,
            rails = baseInput.rails.filterNot { it.id in removedRailIds } +
                addedRailCurves.map { (railId, curve) -> TopologyInputRail(railId, curve.totalArcLength) },
            continuations = continuations.mapValues { (_, targets) -> targets.toSet() },
            atGradeCrossings = rawBaseCrossings.filterNot { crossing ->
                crossing.first.railId in removedRailIds || crossing.second.railId in removedRailIds
            } + addedCrossings
        )
        val expectedConnections = linkedMapOf<
            work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef,
            Set<work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef>
            >()
        baseInput.continuations.forEach { (endpoint, targets) ->
            if (endpoint.railId !in removedRailIds && targets.any { it.railId in removedRailIds }) {
                expectedConnections[endpoint] = targets.filterTo(linkedSetOf()) { it.railId !in removedRailIds }
            }
        }
        addedRailCurves.keys.forEach { railId ->
            work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint.entries.forEach { endpoint ->
                expectedConnections.putIfAbsent(
                    work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef(railId, endpoint),
                    emptySet()
                )
            }
        }
        expectedConnections.putAll(finalConnections)
        val expectedCrossings = linkedMapOf<
            work.art1st.javarailways.core.physics.rail.AtGradeRailCrossingIndex.RailPair,
            work.art1st.javarailways.core.api.physics.rail.topology.AtGradeRailCrossing?
            >()
        rawBaseCrossings.asSequence()
            .filter { crossing ->
                crossing.first.railId in removedRailIds || crossing.second.railId in removedRailIds
            }
            .forEach { crossing ->
                expectedCrossings[
                    work.art1st.javarailways.core.physics.rail.AtGradeRailCrossingIndex.RailPair.of(
                        crossing.first.railId,
                        crossing.second.railId
                    )
                ] = null
            }
        addedCrossings.forEach { crossing ->
            expectedCrossings[
                work.art1st.javarailways.core.physics.rail.AtGradeRailCrossingIndex.RailPair.of(
                    crossing.first.railId,
                    crossing.second.railId
                )
            ] = crossing
        }
        return createPreparedRailTopologyUpdate(
            baseRoot = baseRoot,
            targetRoot = TopologyStateBuilder.build(targetInput),
            expectedRailCount = targetRailIds.size,
            finalConnectionOrder = finalConnectionOrder,
            finalActiveEndpoints = finalActiveEndpoints,
            expectedAddedRailCurves = addedRailCurves,
            removedRailIds = removedRailIds,
            expectedConnections = expectedConnections,
            expectedCrossings = expectedCrossings
        )
    }

    /** Captures only the structural state named by one prepared edit for bounded rollback checks. */
    @JvmSynthetic
    internal fun createPreparedRailTopologyUpdate(
        baseRoot: TopologyState,
        targetRoot: TopologyState,
        expectedRailCount: Int,
        finalConnectionOrder: Map<RailEndpointRef, List<RailEndpointRef>>,
        finalActiveEndpoints: Map<RailEndpointRef, RailEndpointRef?>,
        expectedAddedRailCurves: Map<UUID, PrimeCurve>,
        removedRailIds: Set<UUID>,
        expectedConnections: Map<RailEndpointRef, Set<RailEndpointRef>>,
        expectedCrossings: Map<AtGradeRailCrossingIndex.RailPair, AtGradeRailCrossing?>
    ): PreparedRailTopologyUpdate {
        val affectedRailIds = linkedSetOf<UUID>().apply {
            addAll(expectedAddedRailCurves.keys)
            addAll(removedRailIds)
            expectedConnections.forEach { (endpoint, targets) ->
                add(endpoint.railId)
                targets.mapTo(this, RailEndpointRef::railId)
            }
            expectedCrossings.keys.forEach { pair ->
                add(pair.first)
                add(pair.second)
            }
        }
        return railTopologyLock.read {
            ensureTopologyHealthy()
            check(publishedTopologyState === baseRoot) {
                "Prepared topology is stale while capturing its rollback boundary"
            }
            val baseRails = affectedRailIds.associateWith(rails::get)
            val baseRailSnapshot = rails.values.map(CoreRail::save).sortedBy(CoreRail.SavedRailV1::id)
            val targetInput = CoreTopologyInputAdapter.capture(targetRoot)
            val targetRailSnapshot = buildPreparedTargetRailSnapshot(
                baseRailSnapshot,
                targetInput.rails.mapTo(linkedSetOf(), TopologyInputRail::id),
                targetInput.continuations,
                finalConnectionOrder,
                expectedAddedRailCurves,
                finalActiveEndpoints
            )
            val baseConnections = linkedMapOf<
                RailEndpointRef,
                Set<RailEndpointRef>
                >()
            baseRails.values.filterNotNull().forEach { rail ->
                RailEndpoint.entries.forEach { endpoint ->
                    val ref = RailEndpointRef(rail.id, endpoint)
                    baseConnections[ref] = endpointConnections(ref)
                }
            }
            PreparedRailTopologyUpdate(
                ownerWorld = this,
                baseRoot = baseRoot,
                targetRoot = targetRoot,
                baseRailSnapshot = baseRailSnapshot,
                targetRailSnapshot = targetRailSnapshot,
                expectedRailCount = expectedRailCount,
                baseRailCount = rails.size,
                baseRails = baseRails,
                baseConnections = baseConnections,
                baseCrossings = expectedCrossings.keys.associateWith(atGradeRailCrossingIndex::crossing),
                expectedAddedRailCurves = expectedAddedRailCurves,
                removedRailIds = removedRailIds,
                expectedConnections = expectedConnections,
                expectedCrossings = expectedCrossings
            )
        }
    }

    private fun buildPreparedTargetRailSnapshot(
        baseSnapshot: List<CoreRail.SavedRailV1>,
        targetRailIds: Set<UUID>,
        targetConnections: Map<RailEndpointRef, Set<RailEndpointRef>>,
        finalConnectionOrder: Map<RailEndpointRef, List<RailEndpointRef>>,
        addedRailCurves: Map<UUID, PrimeCurve>,
        finalActiveEndpoints: Map<RailEndpointRef, RailEndpointRef?>
    ): List<CoreRail.SavedRailV1> {
        val baseById = baseSnapshot.associateBy { saved -> UUID.fromString(saved.id) }
        fun connections(endpoint: RailEndpointRef, base: List<String>): List<String> {
            val targetIds = targetConnections[endpoint].orEmpty().mapTo(linkedSetOf(), RailEndpointRef::railId)
            val explicit = finalConnectionOrder[endpoint]
            if (explicit != null) return explicit.map { it.railId.toString() }
            val retained = base.filter { UUID.fromString(it) in targetIds }
            val retainedIds = retained.mapTo(hashSetOf(), UUID::fromString)
            return retained + (targetIds - retainedIds).map(UUID::toString).sorted()
        }
        fun active(endpoint: RailEndpointRef, connected: List<String>, base: String?): String? {
            val explicit = finalActiveEndpoints[endpoint]?.railId?.toString()
            return when {
                endpoint in finalActiveEndpoints -> explicit
                base in connected -> base
                else -> connected.firstOrNull()
            }
        }
        return targetRailIds.sortedBy(UUID::toString).map { railId ->
            val base = baseById[railId]
            val curve = addedRailCurves[railId]?.save() as? work.art1st.javarailways.core.physics.rail.curve.PrimeCurveImpl.SavedPrimeCurveV1
                ?: requireNotNull(base) { "missing curve for prepared target rail $railId" }.curveAB
            val endpointA = RailEndpointRef(railId, RailEndpoint.A)
            val endpointB = RailEndpointRef(railId, RailEndpoint.B)
            val atA = connections(endpointA, base?.nextRailAtA.orEmpty())
            val atB = connections(endpointB, base?.nextRailAtB.orEmpty())
            CoreRail.SavedRailV1(
                id = railId.toString(),
                curveAB = curve,
                nextRailAtA = atA,
                nextRailAtB = atB,
                activeTrackAtA = active(endpointA, atA, base?.activeTrackAtA),
                activeTrackAtB = active(endpointB, atB, base?.activeTrackAtB)
            )
        }
    }

    /** Applies one already validated stable-identity snapshot plan inside the caller's transaction. */
    @JvmSynthetic
    @MainThread
    internal fun applyPreparedRailWorldSnapshot(plan: PreparedRailWorldSnapshotPlan) {
        check(railTopologyLock.isWriteLockedByCurrentThread) {
            "Prepared rail-world snapshot restore requires an outer topology transaction"
        }
        plan.requireReadyToApply(this)
        val preservedActiveEndpoints = plan.activeSelectionEndpoints().associateWith { endpoint ->
            getRail(endpoint.railId)?.let { rail ->
                val sourceView = if (endpoint.endpoint == RailEndpoint.A) rail.viewBA else rail.viewAB
                sourceView.activeRail?.let { active ->
                    RailEndpointRef(
                        active.rail.id,
                        if (active.viewDirection == ViewDirection.AB) RailEndpoint.A else RailEndpoint.B
                    )
                }
            }
        }
        val removed = plan.removedOrReplacedRailIds.map { railId ->
            requireNotNull(rails[railId]) { "snapshot replacement source $railId disappeared" }
        }

        // Disconnect all removed instances in one pass, then remove their geometry/index entries.
        val removedInstances = removed.toSet()
        rails.values.asSequence().filterNot(removedInstances::contains).flatMap { rail ->
            sequenceOf(rail.viewAB, rail.viewBA)
        }.forEach { view ->
            if (view.activeRail?.rail in removedInstances) view.activeRail = null
            view.mutableNextRails.removeIf { it.rail in removedInstances }
            if (view.activeRail == null) view.activeRail = view.mutableNextRails.firstOrNull()
        }
        removed.forEach { rail ->
            rail.destroy()
            rail.viewAB.activeRail = null
            rail.viewBA.activeRail = null
            rail.viewAB.mutableNextRails.clear()
            rail.viewBA.mutableNextRails.clear()
            rails.remove(rail.id)
            atGradeRailCrossingIndex.remove(rail.id)
            railSpatialIndex.removeRail(rail)
        }

        plan.preparedCreations?.let { batch ->
            val createdRails = batch.creations.map { creation ->
                require(creation.railId !in rails) { "snapshot target rail already exists: ${creation.railId}" }
                CoreRail.createUnregistered(creation.railId, this, creation.curve)
            }
            val byId = createdRails.associateBy(CoreRail::id)
            createdRails.forEach { rails[it.id] = it }
            atGradeRailCrossingIndex.installPrepared(byId, batch.sampledRails, batch.crossings)
        }
        if (removed.isNotEmpty() || plan.preparedCreations != null) {
            railTopologyTransactionContext.get()?.recordRailInventoryMutation(
                plan.removedOrReplacedRailIds + plan.createdOrReplacedRailIds
            )
        }

        val endpointsToApply = LinkedHashSet<work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef>().apply {
            addAll(plan.changedExistingEndpoints)
            plan.createdOrReplacedRailIds.forEach { railId ->
                work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint.entries.forEach { endpoint ->
                    add(work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef(railId, endpoint))
                }
            }
            toList().forEach { endpoint ->
                plan.targetEndpointStates.getValue(endpoint).connectedEndpoints.forEach(::add)
            }
        }
        fun view(endpoint: work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef): RailView {
            val rail = requireNotNull(rails[endpoint.railId]) { "missing snapshot target rail ${endpoint.railId}" }
            return if (endpoint.endpoint == work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint.A) {
                rail.viewBA
            } else rail.viewAB
        }
        endpointsToApply.forEach { endpoint ->
            val target = plan.targetEndpointStates.getValue(endpoint)
            val sourceView = view(endpoint)
            sourceView.activeRail = null
            sourceView.mutableNextRails.clear()
            sourceView.mutableNextRails.addAll(target.connectedEndpoints.map { view(it).reversed })
            val activeEndpoint = if (plan.preserveCurrentActiveSelections) {
                preservedActiveEndpoints[endpoint]?.takeIf(target.connectedEndpoints::contains)
                    ?: target.connectedEndpoints.firstOrNull()
            } else {
                target.activeEndpoint
            }
            sourceView.activeRail = activeEndpoint?.let { view(it).reversed }
        }
    }

    /** Convenience wrapper preserving the single input/output shape while using batch semantics. */
    @OptIn(Heavy::class)
    fun prepareRailCreation(
        curve: PrimeCurve,
        excludedExistingRailIds: Set<UUID> = emptySet()
    ): PreparedRailCreationBatch = prepareRailCreationBatch(listOf(curve), excludedExistingRailIds)

    /**
     * Installs one prepared batch on the world owner thread. Validation completes before any map or
     * index mutation. A replacement batch must join a clean outer topology transaction so its
     * excluded rails can be removed before that transaction publishes.
     */
    @MainThread
    fun installPreparedRails(batch: PreparedRailCreationBatch): List<CoreRail> {
        rejectPreparedWorldMismatch(batch)
        val activeContext = railTopologyTransactionContext.get()
        if (batch.excludedExistingRailIds.isNotEmpty() && activeContext == null) {
            rejectPrepared(
                PreparedRailCreationRejectionReason.INVALID_BATCH,
                "Prepared replacement rails must be installed inside their complete topology transaction"
            )
        }
        return if (activeContext != null) {
            installPreparedRailsInTransaction(batch, activeContext)
        } else {
            railTopologyTransaction {
                installPreparedRailsInTransaction(
                    batch,
                    checkNotNull(railTopologyTransactionContext.get())
                )
            }
        }
    }

    private fun installPreparedRailsInTransaction(
        batch: PreparedRailCreationBatch,
        context: RailTopologyTransactionContext
    ): List<CoreRail> {
        check(railTopologyLock.isWriteLockedByCurrentThread) {
            "Prepared rail installation must run inside a rail topology transaction"
        }
        rejectPreparedWorldMismatch(batch)
        if (context.railInventoryMutated) {
            rejectPrepared(
                PreparedRailCreationRejectionReason.DIRTY_OUTER_TRANSACTION,
                "The active topology transaction changed the rail inventory before prepared installation"
            )
        }
        if (publishedTopologyState !== batch.topologyRoot) {
            rejectPrepared(
                PreparedRailCreationRejectionReason.STALE_TOPOLOGY,
                "Rail topology changed from revision ${batch.topologyRevision}; rebuild and retry"
            )
        }
        val conflictingIds = batch.creations.map(PreparedRailCreation::railId).filter(rails::containsKey)
        if (conflictingIds.isNotEmpty()) {
            rejectPrepared(
                PreparedRailCreationRejectionReason.INVALID_BATCH,
                "Prepared rail IDs already exist: ${conflictingIds.sortedBy(UUID::toString)}"
            )
        }

        val createdRails = batch.creations.map { creation ->
            CoreRail.createUnregistered(creation.railId, this, creation.curve)
        }
        val railsById = createdRails.associateBy(CoreRail::id)
        context.recordRailInventoryMutation(railsById.keys)
        createdRails.forEach { rail -> rails[rail.id] = rail }
        atGradeRailCrossingIndex.installPrepared(
            railsById = railsById,
            preparedRails = batch.sampledRails,
            crossings = batch.crossings
        )
        context.requireRemoval(
            createdRailIds = railsById.keys,
            excludedRailIds = batch.excludedExistingRailIds
        )
        return createdRails
    }

    private fun rejectPreparedWorldMismatch(batch: PreparedRailCreationBatch) {
        if (batch.ownerWorld !== this) {
            rejectPrepared(
                PreparedRailCreationRejectionReason.WORLD_MISMATCH,
                "Prepared rails belong to another CoreWorld instance"
            )
        }
    }

    private fun rejectPrepared(reason: PreparedRailCreationRejectionReason, message: String): Nothing =
        throw PreparedRailCreationRejectedException(reason, message)

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
        railTopologyTransactionContext.get()?.recordRailInventoryMutation(setOf(id))
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
            val context = RailTopologyTransactionContext(captureRailTopologyFingerprint(), null)
            railTopologyTransactionContext.set(context)
            try {
                val result = action()
                context.nestedFailure?.let { nestedFailure ->
                    throw IllegalStateException(
                        "A nested rail topology transaction failed even though its caller caught the exception",
                        nestedFailure
                    )
                }
                context.replacementRequirements.firstOrNull { requirement ->
                    !requirement.isCompleteIn(rails.keys)
                }?.let { incomplete ->
                    throw IllegalStateException(
                        "Prepared replacement transaction ended with an incomplete rail set; " +
                            "created present=" +
                            incomplete.createdRailIds.filter(rails::containsKey).sortedBy(UUID::toString) +
                            ", excluded present=" +
                            incomplete.excludedRailIds.filter(rails::containsKey).sortedBy(UUID::toString)
                    )
                }
                val after = captureRailTopologyFingerprint()
                if (after != context.before) {
                    val nextRevision = Math.addExact(publishedTopologyState.revision, 1L)
                    val candidateInput = CoreTopologyInputAdapter.capture(
                            nextRevision,
                            rails.values,
                            atGradeRailCrossingIndex.snapshot()
                        )
                    val candidate = TopologyStateBuilder.build(candidateInput)
                    val oldEntries = checkNotNull(context.before).rails.associateBy { it.rail.id }
                    val newEntries = after.rails.associateBy { it.rail.id }
                    val changed = (oldEntries.keys + newEntries.keys).filterTo(linkedSetOf()) {
                        oldEntries[it] != newEntries[it]
                    }
                    notifyTopologyChange(work.art1st.javarailways.core.api.physics.rail.topology.RailTopologyChange(
                        publishedTopologyState, candidate,
                        work.art1st.javarailways.core.physics.rail.topologyAffectedRails(publishedTopologyState, candidate, changed)
                    ))
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
     * Commits one online edit whose complete normalized topology was already built off-thread.
     * Validation and publication are proportional to the declared edit delta.
     */
    @MainThread
    fun <T> railTopologyTransaction(prepared: PreparedRailTopologyUpdate, action: () -> T): T {
        check(railTopologyTransactionContext.get() == null) {
            "A prepared rail topology transaction cannot be nested in another topology transaction"
        }
        check(prepared.ownerWorld === this) { "Prepared topology belongs to another world" }
        check(publishedTopologyState === prepared.baseRoot) {
            "Prepared topology is stale; rebuild it from the current root"
        }
        ensureTopologyHealthy()
        awaitCurrentPhysicsEpoch()
        return railTopologyLock.write {
            ensureTopologyHealthy()
            check(publishedTopologyState === prepared.baseRoot) {
                "Prepared topology is stale; rebuild it from the current root"
            }
            val context = RailTopologyTransactionContext(null, prepared.inventoryMutationRailIds)
            railTopologyTransactionContext.set(context)
            try {
                val result = action()
                context.nestedFailure?.let { nestedFailure ->
                    throw IllegalStateException(
                        "A nested rail topology transaction failed even though its caller caught the exception",
                        nestedFailure
                    )
                }
                validateReplacementRequirements(context)
                validatePreparedTopologyResult(prepared)
                prepared.topologyChange?.let(::notifyTopologyChange)
                publishedTopologyState = prepared.targetRoot
                result
            } catch (failure: Throwable) {
                val baseRestored = runCatching { matchesPreparedTopologyBase(prepared) }.getOrDefault(false)
                if (!baseRestored) faultTopologyAfterFailedMutation(failure)
                throw failure
            } finally {
                railTopologyTransactionContext.remove()
            }
        }
    }

    private fun validateReplacementRequirements(context: RailTopologyTransactionContext) {
        context.replacementRequirements.firstOrNull { requirement -> !requirement.isCompleteIn(rails.keys) }
            ?.let { incomplete ->
                throw IllegalStateException(
                    "Prepared replacement transaction ended with an incomplete rail set; created present=" +
                        incomplete.createdRailIds.filter(rails::containsKey).sortedBy(UUID::toString) +
                        ", excluded present=" +
                        incomplete.excludedRailIds.filter(rails::containsKey).sortedBy(UUID::toString)
                )
            }
    }

    private fun validatePreparedTopologyResult(prepared: PreparedRailTopologyUpdate) {
        check(rails.size == prepared.expectedRailCount) { "Prepared topology rail count does not match committed edit" }
        check(prepared.expectedAbsentRailIds.none(rails::containsKey)) {
            "Prepared topology removal is incomplete"
        }
        prepared.expectedAddedRailCurves.forEach { (railId, expectedCurve) ->
            val rail = requireNotNull(rails[railId]) { "Prepared topology is missing added rail $railId" }
            check(rail.curve === expectedCurve) { "Prepared topology rail geometry changed for $railId" }
        }
        prepared.expectedConnections.forEach { (endpoint, expectedTargets) ->
            check(endpointConnections(endpoint) == expectedTargets) {
                "Prepared topology endpoint result differs at $endpoint"
            }
        }
        prepared.expectedCrossings.forEach { (pair, expectedCrossing) ->
            check(atGradeRailCrossingIndex.crossing(pair) == expectedCrossing) {
                "Prepared topology crossing result differs for $pair"
            }
        }
    }

    private fun matchesPreparedTopologyBase(prepared: PreparedRailTopologyUpdate): Boolean {
        if (rails.size != prepared.baseRailCount) return false
        if (prepared.baseRails.any { (railId, expectedRail) -> rails[railId] !== expectedRail }) return false
        if (prepared.baseConnections.any { (endpoint, expectedTargets) ->
                rails[endpoint.railId] == null || endpointConnections(endpoint) != expectedTargets
            }
        ) return false
        if (prepared.baseCrossings.any { (pair, expectedCrossing) ->
                atGradeRailCrossingIndex.crossing(pair) != expectedCrossing
            }
        ) return false
        return true
    }

    private fun endpointConnections(
        endpoint: RailEndpointRef
    ): Set<RailEndpointRef> {
        val rail = requireNotNull(rails[endpoint.railId]) { "Rail topology is missing endpoint $endpoint" }
        val view = if (endpoint.endpoint == RailEndpoint.A) rail.viewBA else rail.viewAB
        return view.mutableNextRails.mapTo(linkedSetOf()) { next ->
            RailEndpointRef(
                next.rail.id,
                if (next.viewDirection == ViewDirection.AB) RailEndpoint.A else RailEndpoint.B
            )
        }
    }

    private fun faultTopologyAfterFailedMutation(failure: Throwable) {
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

    /**
     * Runtime-only structural topology revision, unrelated to persisted snapshot revisions.
     * The value advances only when a successful outer transaction publishes a changed graph.
     */
    val topologyRevision: Long
        get() = publishedTopologyState.revision

    override val topologyState: TopologyState
        get() = publishedTopologyState

    override fun registerTopologyChangeListener(
        listener: work.art1st.javarailways.core.api.physics.rail.topology.RailTopologyChangeListener
    ): AutoCloseable {
        railTopologyLock.read { topologyListeners.add(listener) }
        val closed = java.util.concurrent.atomic.AtomicBoolean()
        return AutoCloseable { if (closed.compareAndSet(false, true)) topologyListeners.remove(listener) }
    }

    private fun notifyTopologyChange(change: work.art1st.javarailways.core.api.physics.rail.topology.RailTopologyChange) {
        topologyListeners.forEach { it.onTopologyChange(change) }
    }

    fun isRailTopologyTransactionActiveOnCurrentThread(): Boolean =
        railTopologyLock.isWriteLockedByCurrentThread

    val allRails: Collection<CoreRail>
        get() = railTopologyLock.read { rails.values.toList() }

    @JvmSynthetic
    internal fun railInventorySize(): Int = railTopologyLock.read { rails.size }

    @JvmSynthetic
    internal fun preparedInstallIsOnlyInventoryMutation(): Boolean =
        railTopologyLock.isWriteLockedByCurrentThread &&
            railTopologyTransactionContext.get()?.railInventoryMutationCount == 1

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
            if (rails.containsKey(removed.id)) {
                railTopologyTransactionContext.get()?.recordRailInventoryMutation(setOf(removed.id))
            }
            val connectedEndpointViews = sequenceOf(removed.viewAB, removed.viewBA)
                .flatMap { it.mutableNextRails.asSequence() }
                .map(RailView::reversed)
                .distinct()
                .toList()
            val affectedRailIds = buildSet {
                add(removed.id)
                connectedEndpointViews.mapTo(this) { it.rail.id }
            }
            val occupyingTrainIds = occupyingTrainIds(affectedRailIds)
            require(occupyingTrainIds.isEmpty()) {
                "Cannot remove rail ${removed.id}; affected rails are occupied by ${occupyingTrainIds.sorted()}"
            }
            connectedEndpointViews.forEach { view ->
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
     * Captures a coherent candidate after train frames and swept occupancy for [physicsEpoch].
     * The candidate is not externally visible until [publishCapturedWorldFrame] validates it again.
     */
    internal fun captureWorldFrame(physicsEpoch: Long): RailwayWorldFrameSnapshot =
        railTopologyLock.read {
            ensureTopologyHealthy()
            val activeContinuations = captureActiveRailContinuations()
            RailwayWorldFrameSnapshot.createWithOccupancy(
                physicsEpoch = physicsEpoch,
                topologyRevision = publishedTopologyState.revision,
                trains = trains.values.map(CoreTrain::publishedFrame),
                occupancy = sweptOccupancy,
                activeContinuations = activeContinuations
            )
        }

    /** Keeps coordinator publication in topology-before-coordinator lock order. */
    internal fun <T> withRailTopologyReadLock(action: () -> T): T =
        railTopologyLock.read(action)

    /**
     * Publishes an earlier [captureWorldFrame] result only if no constituent state changed while
     * synchronous epoch processors ran.
     */
    internal fun publishCapturedWorldFrame(frame: RailwayWorldFrameSnapshot): RailwayWorldFrameSnapshot =
        railTopologyLock.read {
            ensureTopologyHealthy()
            check(frame.topologyRevision == publishedTopologyState.revision) {
                "rail topology changed while processing physics epoch ${frame.physicsEpoch}"
            }
            check(frame.occupancy === sweptOccupancy) {
                "swept occupancy changed while processing physics epoch ${frame.physicsEpoch}"
            }
            val currentFrames = trains.values.map(CoreTrain::publishedFrame)
                .sortedBy(TrainFrameSnapshot::trainId)
            check(frame.trains == currentFrames) {
                "train frames changed while processing physics epoch ${frame.physicsEpoch}"
            }
            check(frame.activeContinuations === captureActiveRailContinuations()) {
                "active rail continuation changed while processing physics epoch ${frame.physicsEpoch}"
            }
            publishedWorldFrame = frame
            frame
        }

    /** Serializes live branch selection changes with immutable continuation capture. */
    internal fun updateActiveRailSelection(
        shouldChange: () -> Boolean,
        change: () -> Unit
    ) {
        synchronized(activeRailSelectionLock) {
            if (!shouldChange()) return
            val nextRevision = Math.addExact(activeRailSelectionRevision, 1L)
            change()
            activeRailSelectionRevision = nextRevision
        }
    }

    /** Rebuilds only after topology or live branch selection changes; unchanged epochs reuse one value. */
    private fun captureActiveRailContinuations(): ActiveRailContinuationsSnapshot =
        synchronized(activeRailSelectionLock) {
            val topology = publishedTopologyState
            val cached = cachedActiveContinuations
            if (cached.topologyRevision == topology.revision &&
                cached.selectionRevision == activeRailSelectionRevision
            ) {
                return@synchronized cached
            }
            val selections = linkedMapOf<RailEndpointRef, RailEndpointRef>()
            topology.nodes.values.filterIsInstance<TopologyNode.Turnout>().forEach { turnout ->
                turnout.ports.forEach { entry ->
                    val rail = requireNotNull(rails[entry.railId]) {
                        "turnout entry rail is missing from the live world: ${entry.railId}"
                    }
                    val arrivingView = if (entry.endpoint == RailEndpoint.A) rail.viewBA else rail.viewAB
                    arrivingView.activeRail?.let { active ->
                        val exit = RailEndpointRef(
                            active.rail.id,
                            if (active.viewDirection == ViewDirection.AB) RailEndpoint.A else RailEndpoint.B
                        )
                        check(exit in turnout.legalContinuations.getValue(entry)) {
                            "live turnout selection is not a legal topology continuation: $entry -> $exit"
                        }
                        selections[entry] = exit
                    }
                }
            }
            ActiveRailContinuationsSnapshot.create(
                topology.revision,
                activeRailSelectionRevision,
                selections
            ).also { cachedActiveContinuations = it }
        }

    /**
     * Captures and immediately publishes one coherent frame for existing serialized internal
     * callers that have no intervening railway-epoch processing.
     */
    internal fun publishWorldFrame(physicsEpoch: Long): RailwayWorldFrameSnapshot {
        val frame = captureWorldFrame(physicsEpoch)
        publishCapturedWorldFrame(frame)
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

    private class RailTopologyTransactionContext(
        val before: RailTopologyFingerprint?,
        private val allowedInventoryMutationRailIds: Set<UUID>?
    ) {
        var nestedFailure: Throwable? = null
            private set
        val railInventoryMutated: Boolean
            get() = railInventoryMutationCount > 0
        var railInventoryMutationCount: Int = 0
            private set
        val replacementRequirements = mutableListOf<ReplacementRequirement>()

        fun recordNestedFailure(failure: Throwable) {
            if (nestedFailure == null) nestedFailure = failure
        }

        fun recordRailInventoryMutation(railIds: Set<UUID>) {
            allowedInventoryMutationRailIds?.let { allowed ->
                check(railIds.all(allowed::contains)) {
                    "Prepared topology action mutated an undeclared rail identity"
                }
            }
            railInventoryMutationCount++
        }

        fun requireRemoval(createdRailIds: Set<UUID>, excludedRailIds: Set<UUID>) {
            if (excludedRailIds.isNotEmpty()) {
                replacementRequirements += ReplacementRequirement(
                    createdRailIds.toSet(),
                    excludedRailIds.toSet()
                )
            }
        }
    }

    private data class ReplacementRequirement(
        val createdRailIds: Set<UUID>,
        val excludedRailIds: Set<UUID>
    ) {
        fun isCompleteIn(liveRailIds: Set<UUID>): Boolean {
            val committed = createdRailIds.all(liveRailIds::contains) &&
                excludedRailIds.none(liveRailIds::contains)
            val rolledBack = createdRailIds.none(liveRailIds::contains) &&
                excludedRailIds.all(liveRailIds::contains)
            return committed || rolledBack
        }
    }

    private data class CapturedRailCreationCandidates(
        val topologyRoot: TopologyState,
        val candidates: AtGradeRailCrossingIndex.CandidateSnapshot
    )

    internal fun atGradeExactDetectionCountForTesting(): Long =
        atGradeRailCrossingIndex.exactDetectionCount()

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
