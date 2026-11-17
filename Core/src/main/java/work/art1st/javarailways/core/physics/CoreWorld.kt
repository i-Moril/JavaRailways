package work.art1st.javarailways.core.physics

import work.art1st.javarailways.core.physics.infrastructure.InfrastructureCoordinationRegistry
import work.art1st.javarailways.core.api.physics.infrastructure.RailIntervalResource
import work.art1st.javarailways.core.api.physics.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.core.api.physics.infrastructure.StableTopologyNodeKey
import work.art1st.javarailways.core.api.physics.infrastructure.TopologyEditImpact
import work.art1st.javarailways.core.api.physics.infrastructure.TurnoutResourceIdentity
import work.art1st.javarailways.core.api.physics.infrastructure.PhysicalResourceIdentity
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
import work.art1st.javarailways.core.api.physics.rail.topology.RailTopologyChange
import work.art1st.javarailways.core.api.physics.rail.topology.RailTopologyChangeListener
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNode
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.WorldExecutionMode
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.RailwayOccupancySnapshot
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveImpl
import work.art1st.javarailways.core.physics.rail.curve.ReversedCurve
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.api.util.annotation.MainThread
import work.art1st.javarailways.core.api.util.plugin.Extendable
import work.art1st.javarailways.core.api.util.plugin.ExtensionDataContainer
import work.art1st.javarailways.core.api.util.tick.TickManager
import work.art1st.javarailways.core.physics.train.CoreTrain
import work.art1st.javarailways.core.physics.train.CoreCar
import work.art1st.javarailways.core.physics.train.CollisionEnvelope
import work.art1st.javarailways.core.physics.train.TrainCoupling
import work.art1st.javarailways.core.physics.rail.decodeSavedRailGraph
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.calculateRailTopologyTarget
import work.art1st.javarailways.core.physics.rail.prepareRailSnapshotRestoreInputs
import work.art1st.javarailways.core.physics.rail.buildPreparedTargetRailSnapshot
import work.art1st.javarailways.core.physics.rail.AtGradeRailCrossingIndex
import work.art1st.javarailways.core.physics.rail.RailView
import work.art1st.javarailways.core.physics.rail.RailSpatialIndex
import work.art1st.javarailways.core.physics.rail.SweptOccupancyQueryResult
import work.art1st.javarailways.core.physics.rail.PreparedRailCreation
import work.art1st.javarailways.core.physics.rail.PreparedRailCreationBatch
import work.art1st.javarailways.core.physics.rail.PreparedRailCreationRejectedException
import work.art1st.javarailways.core.physics.rail.PreparedRailCreationRejectionReason
import work.art1st.javarailways.core.physics.rail.PreparedRailConnectionPlan
import work.art1st.javarailways.core.physics.rail.PreparedRailEndpointConnection
import work.art1st.javarailways.core.physics.rail.PreparedRailTopologyUpdate
import work.art1st.javarailways.core.physics.rail.PreparedRailWorldSnapshotPlan
import work.art1st.javarailways.core.physics.rail.topologyAffectedRails
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
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
    override val tickManager: TickManager,
    val physicsCoordinator: PhysicsCoordinator,
    private val extensionDataContainer: ExtensionDataContainer<Any> = ExtensionDataContainer(),
    override val executionMode: WorldExecutionMode = WorldExecutionMode.LIVE
) : World, Extendable<Any> by extensionDataContainer {
    private val physicsMaintenance = ThreadLocal<CorePhysicsMaintenance?>()

    override fun <T> withPhysicsStalled(
        action: (work.art1st.javarailways.core.api.physics.PhysicsMaintenance) -> T
    ): T = physicsCoordinator.withPhysicsStalled {
        val parent = physicsMaintenance.get()
        val scope = CorePhysicsMaintenance(this, parent)
        physicsMaintenance.set(scope)
        try {
            val result = action(scope)
            scope.finish()
            result
        } catch (failure: Throwable) {
            scope.fail(failure)
            throw failure
        } finally {
            scope.close()
            physicsMaintenance.set(parent)
        }
    }

    internal fun maintenanceSelectionRevision(): Long = synchronized(activeRailSelectionLock) {
        activeRailSelectionRevision
    }

    override val maximumPhysicsDeltaSeconds: Double
        get() = physicsCoordinator.maximumPhysicsDeltaSeconds

    val executionOwner: WorldExecutionOwner = physicsCoordinator.executionOwner
    private val railSpatialIndex = RailSpatialIndex()
    private val atGradeRailCrossingIndex = AtGradeRailCrossingIndex(railSpatialIndex)
    private val railTopologyLock = ReentrantReadWriteLock()
    private val activeRailSelectionLock = Any()
    private val railTopologyTransactionContext = ThreadLocal<RailTopologyTransactionContext?>()
    private val topologyListeners = CopyOnWriteArrayList<RailTopologyChangeListener>()
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

    /** The single world-local arbiter shared by Core mutation and higher-level route claims. */
    val infrastructureCoordination = InfrastructureCoordinationRegistry(
        currentTopologyRevision = { topologyRevision },
        resourceExists = { resource ->
            when (resource) {
                is RailIntervalResource -> publishedTopologyState.railProjection[resource.railId]?.singleOrNull()?.let {
                    resource.endAbMeters <= kotlin.math.abs(it.railEndAbMeters - it.railStartAbMeters)
                } == true
                is StableInfrastructureResource -> physicalResourceExists(resource)
            }
        },
        railExists = { publishedTopologyState.railProjection.containsKey(it) },
        topologyNodeExists = { publishedTopologyState.nodes.values.any { node -> node.ports.toSet() == it.ports.toSet() } }
    )

    private fun physicalResourceExists(resource: StableInfrastructureResource): Boolean {
        val firstRailId = try {
            UUID.fromString(resource.resourceId.substringBefore('@').substringBefore('/'))
        } catch (_: IllegalArgumentException) {
            return false
        }
        return when (resource.typeId) {
            TurnoutResourceIdentity.TYPE_ID -> RailEndpoint.entries.any { end ->
                val node = publishedTopologyState.structuralNodeAt(RailEndpointRef(firstRailId, end)) as? TopologyNode.Turnout
                node != null && TurnoutResourceIdentity.fromPorts(node.ports) == resource
            }
            PhysicalResourceIdentity.DIAMOND_TYPE_ID -> publishedTopologyState.atGradeCrossingsOn(firstRailId)
                .any { PhysicalResourceIdentity.diamond(it) == resource }
            else -> false
        }
    }

    /** Called before the first mutation; leases remain held through topology publication. */
    internal fun ensureRailEditProtected(railIds: Collection<UUID>) {
        val context = checkNotNull(railTopologyTransactionContext.get()) { "Rail edit requires a topology transaction" }
        context.allowedEditRailIds?.let { allowed ->
            require(railIds.all(allowed::contains)) { "Prepared edit attempted to modify an undeclared rail" }
        }
        val existing = railIds.filterTo(linkedSetOf()) { publishedTopologyState.railProjection.containsKey(it) }
        if (existing.isEmpty()) return
        val nodes = publishedTopologyState.nodes.values.filter { node -> node.ports.any { it.railId in existing } }
        val resources = buildSet {
            nodes.filterIsInstance<TopologyNode.Turnout>().forEach { add(TurnoutResourceIdentity.fromPorts(it.ports)) }
            publishedTopologyState.atGradeCrossings.filter { it.first.railId in existing || it.second.railId in existing }
                .forEach { add(PhysicalResourceIdentity.diamond(it)) }
        }
        context.editProtection += infrastructureCoordination.ensureEditProtected(
            TopologyEditImpact(existing, nodes.map { StableTopologyNodeKey(it.ports) }, resources)
        )
    }

    internal fun <T> withRailSelectionProtection(view: RailView, action: () -> T): T {
        if (railTopologyTransactionContext.get() != null) {
            ensureRailEditProtected(listOf(view.rail.id))
            return action()
        }
        return physicsCoordinator.withStructuralMutation {
            railTopologyLock.read { applyProtectedRailSelection(view, action) }
        }
    }

    private fun <T> applyProtectedRailSelection(view: RailView, action: () -> T): T {
        require(rails[view.rail.id] === view.rail) { "Selection references an unregistered rail" }
        val endpoint = RailEndpointRef(view.rail.id,
            if (view.viewDirection == ViewDirection.AB) RailEndpoint.B else RailEndpoint.A)
        val node = publishedTopologyState.structuralNodeAt(endpoint) as? TopologyNode.Turnout
        val protection = if (node == null) infrastructureCoordination.ensureEditProtected(
            TopologyEditImpact(listOf(view.rail.id), emptyList(), emptyList())
        ) else infrastructureCoordination.ensureSelectionProtected(TurnoutResourceIdentity.fromPorts(node.ports))
        return protection.use {
            val ports = (node?.ports ?: listOf(endpoint)).map { port ->
                val rail = requireNotNull(rails[port.railId]) { "Turnout rail disappeared" }
                val arc = if (port.endpoint == RailEndpoint.A) 0.0 else rail.curve.totalArcLength
                work.art1st.javarailways.core.api.physics.rail.RailInterval(port.railId, arc, arc)
            }
            require(trains.values.none { train -> train.activePlacementEnvelope().any { occupied ->
                ports.any { port ->
                    port.railId == occupied.railId &&
                        occupied.minArcAB <= port.maxArcLengthInAbMeters + Curve.TOLERANCE &&
                        port.minArcLengthInAbMeters <= occupied.maxArcAB + Curve.TOLERANCE
                }
            } }) { "Cannot change an occupied turnout" }
            action()
        }
    }

    init {
        trains.values.forEach { train ->
            check(trainsByName.putIfAbsent(train.name, train) == null) {
                "duplicate train name: ${train.name}"
            }
        }
        physicsCoordinator.attachWorld(this)
    }

    private constructor(
        tickManager: TickManager,
        physicsCoordinator: PhysicsCoordinator,
        executionMode: WorldExecutionMode
    ) : this(LinkedHashMap(), ConcurrentHashMap(), tickManager, physicsCoordinator,
        executionMode = executionMode)

    companion object {
        const val INFRASTRUCTURE_SNAPSHOT_FORMAT_VERSION = 2
        const val RUNTIME_SNAPSHOT_FORMAT_VERSION = 1
        private const val DEFAULT_TRAIN_FRAME_MAILBOX_CAPACITY = 65_536

        fun createEmpty(
            tickManager: TickManager,
            physicsCoordinator: PhysicsCoordinator = PhysicsCoordinator(1),
            executionMode: WorldExecutionMode = WorldExecutionMode.LIVE
        ): CoreWorld = CoreWorld(tickManager, physicsCoordinator, executionMode)

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
            val savedById = linkedMapOf<UUID, CoreRail.SavedRailV1>()
            infrastructure.rails.forEach { saved ->
                val id = UUID.fromString(saved.id)
                require(savedById.put(id, saved) == null) { "duplicate snapshot rail ID: $id" }
            }
            val curves = savedById.mapValues { (_, saved) -> saved.curveAB.load(Unit) }
            val endpointStates = decodeSavedRailGraph(savedById, curves)
            val world = CoreWorld(
                dependency.tickManager,
                dependency.physicsCoordinator,
                dependency.executionMode
            )
            return world.physicsCoordinator.withStructuralMutation {
                world.railTopologyTransaction {
                    val railDependency = CoreRail.SavedRailV1.Dependency(world)
                    val loadedRails = infrastructure.rails.map { saved ->
                        saved.loadDisconnected(railDependency, curves.getValue(UUID.fromString(saved.id)))
                    }
                    infrastructure.rails.zip(loadedRails).forEach { (savedRail, rail) ->
                        savedRail.restoreValidatedConnections(
                            rail, railDependency,
                            endpointStates.getValue(RailEndpointRef(rail.id, RailEndpoint.A)),
                            endpointStates.getValue(RailEndpointRef(rail.id, RailEndpoint.B))
                        )
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
                world
            }
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

    /**
     * Preflight a new ordinary train before allocating its cars. Its default fixed unit uses [id],
     * which may still belong to another consist after coupling retired the original train.
     * This read does not reserve the identity; construction performs the final checks again.
     */
    @MainThread
    fun validateDefaultTrainCreationIdentity(id: String, name: String = id) {
        physicsCoordinator.ensureBetweenFrames("CoreWorld.validateDefaultTrainCreationIdentity")
        validateNewTrainIdentity(id, name)
        require(trains.values.none { train -> train.composition.units.any { it.unitId == id } }) {
            "fixed unit ID is already in use: $id"
        }
    }

    @MainThread
    /** Unit identities are reserved by staged train registrations as well as committed trains. */
    internal fun validateNewTrainComposition(
        composition: work.art1st.javarailways.core.api.physics.train.TrainComposition,
        transferringFrom: CoreTrain? = null
    ) {
        physicsCoordinator.ensureBetweenFrames("CoreWorld.validateNewTrainComposition")
        val unitIds = composition.units.map { it.unitId }.toSet()
        require(trains.values.none { existing ->
            existing !== transferringFrom && existing.composition.units.any { it.unitId in unitIds }
        }) { "A fixed unit cannot belong to two trains in the same world" }
    }

    internal fun addTrain(train: CoreTrain) {
        physicsCoordinator.ensureBetweenFrames("CoreWorld.addTrain")
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

    /**
     * Rename one exact registered train during the coordinator's between-frames scope.
     * External callers submit the complete operation through `submitBetweenFrames`; validation
     * happens when it executes. The next normal physics publication carries the new name, so this
     * metadata edit never replaces the physical frame or occupancy evidence on its own.
     */
    @MainThread
    fun renameTrain(train: CoreTrain, newName: String) {
        physicsCoordinator.ensureBetweenFrames("CoreWorld.renameTrain")
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
        } catch (failure: Exception) {
            trainsByName.remove(newName, train)
            throw failure
        }
    }

    override val allTrains: Collection<CoreTrain>
        // A staged split suffix still shares source unit identities until both structures commit.
        // Identity lookups remain registered so preparation cannot reuse its train ID or name.
        get() = trains.values.filterNot { it.compositionTransferPending }

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
    internal fun unregisterTrain(train: Train) {
        physicsCoordinator.ensureBetweenFrames("CoreWorld.unregisterTrain")
        if (trains.remove(train.id, train)) {
            publishedTrainFrames.remove(train.id)
        }
        trainsByName.remove(train.name, train)
        (train as? CoreTrain)?.retirePublicationOwnership()
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
        val canonicalCreations = creationsInOrder.map { creation ->
            val curve = creation.curve
            creation.copy(curve = canonicalRailCurve(curve))
        }
        val sampledRails = canonicalCreations.map { creation ->
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
            creations = canonicalCreations,
            excludedExistingRailIds = excludedExistingRailIds,
            sampledRails = sampledRails,
            crossings = crossings,
            candidateSnapshot = captured.candidates
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
        fun normalized(saved: CoreRail.SavedRailV1): CoreRail.SavedRailV1 =
            if (preserveCurrentActiveSelections) saved.copy(activeConnectionAtA = null, activeConnectionAtB = null) else saved
        val requestedBefore = currentSnapshot.associateBy { UUID.fromString(it.id) }
        val requestedAfter = targetSnapshot.associateBy { UUID.fromString(it.id) }
        require(requestedBefore.size == currentSnapshot.size && requestedAfter.size == targetSnapshot.size) {
            "Rail snapshot contains duplicate identities"
        }
        val changedIds = (requestedBefore.keys + requestedAfter.keys).filterTo(linkedSetOf()) {
            requestedBefore[it]?.let(::normalized) != requestedAfter[it]?.let(::normalized)
        }
        val captured = railTopologyLock.read {
            ensureTopologyHealthy()
            val current = rails.mapValues { it.value.save() }
            require(changedIds.all { current[it]?.let(::normalized) == requestedBefore[it]?.let(::normalized) }) {
                "A rail changed since the requested snapshot edit was recorded"
            }
            Triple(publishedTopologyState, rails.toMap(), atGradeRailCrossingIndex.snapshot()) to current
        }
        val currentValues = captured.second
        val targetValues = currentValues.toMutableMap().apply {
            changedIds.forEach { id ->
                val target = requestedAfter[id]
                if (target == null) remove(id) else put(id, target)
            }
        }
        val inputs = prepareRailSnapshotRestoreInputs(currentValues.values.toList(), targetValues.values.toList())
        val prepared = inputs.targetCreations.takeIf(List<PreparedRailCreation>::isNotEmpty)?.let { creations ->
            prepareRailCreationBatchWithIds(creations, inputs.removedOrReplacedRailIds)
        }
        return PreparedRailWorldSnapshotPlan.create(
            world = this,
            topologyRoot = captured.first.first,
            currentById = captured.first.second,
            currentSaved = inputs.currentSaved,
            targetSaved = inputs.targetSaved,
            currentCurves = inputs.currentCurves,
            targetCurves = inputs.targetCurves,
            removedOrReplacedRailIds = inputs.removedOrReplacedRailIds,
            createdOrReplacedRailIds = inputs.createdOrReplacedRailIds,
            preparedCreations = prepared,
            rawCurrentCrossings = captured.first.third,
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
            finalJoinedEndpoints = orderedFinalConnections.associate { it.endpoint to it.joinedEndpoints },
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
        finalConnections: Collection<PreparedRailEndpointConnection>
    ): PreparedRailConnectionPlan = railTopologyLock.read {
        ensureTopologyHealthy()
        PreparedRailConnectionPlan.captureLocked(this, preparedBatch, finalConnections)
    }

    /** Derives replacement and added endpoint states under one coherent topology read lock. */
    @JvmSynthetic
    fun capturePreparedRailConnectionPlan(
        preparedBatch: PreparedRailCreationBatch,
        replacementEndpoints: Map<RailEndpointRef, RailEndpointRef>,
        addedConnections: Collection<Pair<RailEndpointRef, RailEndpointRef>>
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
            require(publishedTopologyState.preservesRails(baseRoot, removedRailIds)) {
                "A rail selected for removal changed"
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
            finalJoinedEndpoints = emptyMap(),
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
        finalConnections: Map<RailEndpointRef, Set<RailEndpointRef>>,
        finalJoinedEndpoints: Map<RailEndpointRef, Set<RailEndpointRef>>,
        finalConnectionOrder: Map<RailEndpointRef, List<RailEndpointRef>>,
        finalActiveEndpoints: Map<RailEndpointRef, RailEndpointRef?>,
        rawBaseCrossings: List<AtGradeRailCrossing>,
        addedCrossings: List<AtGradeRailCrossing>
    ): PreparedRailTopologyUpdate {
        val target = calculateRailTopologyTarget(
            baseRoot = baseRoot,
            baseInput = baseInput,
            removedRailIds = removedRailIds,
            addedRailCurves = addedRailCurves,
            finalConnections = finalConnections,
            finalJoinedEndpoints = finalJoinedEndpoints,
            finalConnectionOrder = finalConnectionOrder,
            finalActiveEndpoints = finalActiveEndpoints,
            rawBaseCrossings = rawBaseCrossings,
            addedCrossings = addedCrossings
        )
        return createPreparedRailTopologyUpdate(
            baseRoot = baseRoot,
            targetRoot = target.targetRoot,
            expectedRailCount = target.expectedRailCount,
            finalConnectionOrder = finalConnectionOrder,
            finalActiveEndpoints = finalActiveEndpoints,
            expectedAddedRailCurves = addedRailCurves,
            removedRailIds = removedRailIds,
            expectedConnections = target.expectedConnections,
            expectedJoinedEndpoints = target.expectedJoinedEndpoints,
            expectedCrossings = target.expectedCrossings
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
        expectedJoinedEndpoints: Map<RailEndpointRef, Set<RailEndpointRef>>,
        expectedCrossings: Map<AtGradeRailCrossingIndex.RailPair, AtGradeRailCrossing?>
    ): PreparedRailTopologyUpdate {
        val affectedRailIds = linkedSetOf<UUID>().apply {
            addAll(expectedAddedRailCurves.keys)
            addAll(removedRailIds)
            expectedConnections.forEach { (endpoint, targets) ->
                add(endpoint.railId)
                targets.mapTo(this, RailEndpointRef::railId)
            }
            expectedJoinedEndpoints.forEach { (endpoint, peers) ->
                add(endpoint.railId)
                peers.mapTo(this, RailEndpointRef::railId)
            }
            expectedCrossings.keys.forEach { pair ->
                add(pair.first)
                add(pair.second)
            }
        }
        return railTopologyLock.read {
            ensureTopologyHealthy()
            require(publishedTopologyState.preservesRails(baseRoot, affectedRailIds.filter { it in baseRoot.railProjection })) {
                "An affected rail changed while capturing the edit boundary"
            }
            val baseRails = affectedRailIds.associateWith(rails::get)
            val baseRailSnapshot = rails.values.map(CoreRail::save).sortedBy(CoreRail.SavedRailV1::id)
            val liveInput = CoreTopologyInputAdapter.capture(publishedTopologyState)
            val targetIds = (rails.keys - removedRailIds) + expectedAddedRailCurves.keys
            val targetConnections = linkedMapOf<RailEndpointRef, Set<RailEndpointRef>>()
            liveInput.continuations.filterKeys { it.railId in targetIds }.forEach { (port, targets) ->
                targetConnections[port] = targets.filterTo(linkedSetOf()) { it.railId in targetIds }
            }
            targetConnections.putAll(expectedConnections)
            val targetJoined: MutableMap<RailEndpointRef, Set<RailEndpointRef>> = liveInput.joinedEndpoints.filterKeys { it.railId in targetIds }
                .mapValues { (_, peers) -> peers.filterTo(linkedSetOf()) { it.railId in targetIds } }.toMutableMap()
            targetJoined.putAll(expectedJoinedEndpoints)
            val targetRailSnapshot = buildPreparedTargetRailSnapshot(
                baseRailSnapshot,
                targetIds,
                targetConnections,
                targetJoined,
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
                expectedRailCount = targetRailSnapshot.size,
                baseRailCount = rails.size,
                baseRails = baseRails,
                baseConnections = baseConnections,
                baseJoinedEndpoints = baseConnections.keys.associateWith { endpoint -> endpointJoinedEndpoints(endpoint) },
                baseCrossings = expectedCrossings.keys.associateWith(atGradeRailCrossingIndex::crossing),
                expectedAddedRailCurves = expectedAddedRailCurves,
                removedRailIds = removedRailIds,
                expectedConnections = expectedConnections,
                expectedJoinedEndpoints = expectedJoinedEndpoints,
                expectedCrossings = expectedCrossings
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
        plan.preparedCreations?.let(::requirePreparedCreationCurrent)
        ensureRailEditProtected(plan.affectedExistingRailIds)
        val preservedActiveEndpoints = plan.activeSelectionEndpoints().mapNotNull { endpoint ->
            getRail(endpoint.railId)?.let { rail ->
                val sourceView = if (endpoint.endpoint == RailEndpoint.A) rail.viewBA else rail.viewAB
                endpoint to sourceView.activeRail?.let { active ->
                    RailEndpointRef(
                        active.rail.id,
                        if (active.viewDirection == ViewDirection.AB) RailEndpoint.A else RailEndpoint.B
                    )
                }
            }
        }.toMap()
        val removed = plan.removedOrReplacedRailIds.map { railId ->
            requireNotNull(rails[railId]) { "snapshot replacement source $railId disappeared" }
        }

        // Disconnect all removed instances in one pass, then remove their geometry/index entries.
        val removedInstances = removed.toSet()
        rails.values.asSequence().filterNot(removedInstances::contains).flatMap { rail ->
            sequenceOf(rail.viewAB, rail.viewBA)
        }.forEach { view ->
            val removedActive = view.activeRail?.rail in removedInstances
            if (removedActive) view.activeRail = null
            view.mutableNextRails.removeIf { it.rail in removedInstances }
            view.mutableJoinedEndpoints.removeIf { it.railId in plan.removedOrReplacedRailIds }
            if (removedActive) view.selectDefaultConnection()
        }
        removed.forEach { rail ->
            rail.viewAB.activeRail = null
            rail.viewBA.activeRail = null
            rail.viewAB.mutableNextRails.clear()
            rail.viewBA.mutableNextRails.clear()
            rail.viewAB.mutableJoinedEndpoints.clear()
            rail.viewBA.mutableJoinedEndpoints.clear()
            rails.remove(rail.id)
            atGradeRailCrossingIndex.remove(rail)
            trackRailCleanup(rail.destroyResources())
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

        val endpointsToApply = LinkedHashSet<RailEndpointRef>().apply {
            addAll(plan.changedExistingEndpoints)
            plan.createdOrReplacedRailIds.forEach { railId ->
                RailEndpoint.entries.forEach { endpoint ->
                    add(RailEndpointRef(railId, endpoint))
                }
            }
            toList().forEach { endpoint ->
                plan.targetEndpointStates.getValue(endpoint).joinedEndpoints.forEach(::add)
            }
        }
        fun view(endpoint: RailEndpointRef): RailView {
            val rail = requireNotNull(rails[endpoint.railId]) { "missing snapshot target rail ${endpoint.railId}" }
            return if (endpoint.endpoint == RailEndpoint.A) {
                rail.viewBA
            } else rail.viewAB
        }
        endpointsToApply.forEach { endpoint ->
            val target = plan.targetEndpointStates.getValue(endpoint)
            val sourceView = view(endpoint)
            sourceView.activeRail = null
            sourceView.mutableNextRails.clear()
            sourceView.mutableNextRails.addAll(target.connectedEndpoints.map { view(it).reversed })
            sourceView.mutableJoinedEndpoints.clear()
            sourceView.mutableJoinedEndpoints.addAll(target.joinedEndpoints)
            val activeEndpoint = if (plan.preserveCurrentActiveSelections) {
                preservedActiveEndpoints[endpoint]?.takeIf(target.connectedEndpoints::contains)
            } else {
                target.activeEndpoint
            }
            sourceView.activeRail = activeEndpoint?.let { view(it).reversed }
            if (target.connectedEndpoints.isEmpty()) sourceView.selectDefaultConnection()
            if (plan.preserveCurrentActiveSelections && sourceView.activeRail == null &&
                (endpoint !in preservedActiveEndpoints || preservedActiveEndpoints[endpoint] != null)
            ) {
                sourceView.selectDefaultConnection()
            }
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
        requirePreparedCreationCurrent(batch)
        val affectedExisting = (batch.excludedExistingRailIds + batch.crossings.flatMap { listOf(it.first.railId, it.second.railId) })
            .filterTo(linkedSetOf(), rails::containsKey)
        ensureRailEditProtected(affectedExisting)
        require(occupyingTrainIds(affectedExisting).isEmpty()) { "Cannot change occupied crossing or replacement rails" }
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

    internal fun requirePreparedCreationCurrent(batch: PreparedRailCreationBatch) {
        rejectPreparedWorldMismatch(batch)
        val currentCandidates = atGradeRailCrossingIndex.captureCandidateSnapshot(
            batch.sampledRails, batch.excludedExistingRailIds
        )
        val excludedCurrent = batch.excludedExistingRailIds.all { id ->
            publishedTopologyState.railIdentities[id] == batch.topologyRoot.railIdentities[id] && rails.containsKey(id)
        }
        if (!excludedCurrent || currentCandidates != batch.candidateSnapshot ||
            currentCandidates.existingRailsByNewRailId.values.flatten().any { candidate ->
                publishedTopologyState.railIdentities[candidate.railId] != batch.topologyRoot.railIdentities[candidate.railId]
            }) {
            rejectPrepared(PreparedRailCreationRejectionReason.STALE_TOPOLOGY,
                "A rail geometry used by the prepared creation changed; rebuild and retry")
        }
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

    private fun canonicalRailCurve(curve: PrimeCurve): PrimeCurveImpl = when (curve) {
        is PrimeCurveImpl -> curve
        is ReversedCurve -> curve.materialize()
        else -> throw IllegalArgumentException("A rail requires a canonical sampled curve")
    }

    /** Core-only primitive for persistent identity restore and atomic bulk topology operations. */
    @JvmSynthetic
    @MainThread
    internal fun createRailInTransaction(id: UUID, curveAB: PrimeCurve): CoreRail {
        check(railTopologyLock.isWriteLockedByCurrentThread) {
            "Rail creation must run inside a rail topology transaction"
        }
        require(id !in rails) { "duplicate rail id in world: $id" }
        val geometry = atGradeRailCrossingIndex.prepareGeometry(id, curveAB)
        val candidates = atGradeRailCrossingIndex.captureCandidateSnapshot(listOf(geometry), emptySet())
        ensureRailEditProtected(candidates.existingRailsByNewRailId.values.flatten().map { it.railId })
        val rail = CoreRail.createUnregistered(
            id = id,
            world = this,
            curveAB = canonicalRailCurve(curveAB)
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
        return physicsCoordinator.withStructuralMutation {
            val previousRoot = publishedTopologyState
            val result = railTopologyLock.write {
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
                    validateReplacementRequirements(context)
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
                        notifyTopologyChange(RailTopologyChange(
                            publishedTopologyState, candidate,
                            topologyAffectedRails(publishedTopologyState, candidate, changed)
                        ))
                        publishedTopologyState = candidate
                    }
                    result
                } catch (failure: Throwable) {
                    if (captureRailTopologyFingerprint() != context.before) {
                        faultTopologyAfterFailedMutation(failure)
                    }
                    throw failure
                } finally {
                    try {
                        context.editProtection.asReversed().forEach(AutoCloseable::close)
                    } finally {
                        railTopologyTransactionContext.remove()
                    }
                }
            }
            if (publishedTopologyState !== previousRoot) {
                try {
                    publishOwnerChanges(emptyList())
                } catch (failure: Throwable) {
                    throw physicsCoordinator.stall("Topology observation publication failed", failure)
                }
            }
            result
        }
    }

    /** Applies the declared local edit to the current world, preserving unrelated committed edits. */
    @MainThread
    fun <T> railTopologyTransaction(prepared: PreparedRailTopologyUpdate, action: () -> T): T {
        check(railTopologyTransactionContext.get() == null) {
            "A prepared rail topology transaction cannot be nested in another topology transaction"
        }
        check(prepared.ownerWorld === this) { "Prepared topology belongs to another world" }
        return railTopologyTransaction {
            require(matchesPreparedTopologyBase(prepared)) {
                "An object or connection used by the prepared edit has changed"
            }
            val context = checkNotNull(railTopologyTransactionContext.get())
            context.allowedInventoryMutationRailIds = prepared.inventoryMutationRailIds
            context.allowedEditRailIds = prepared.baseRails.keys
            ensureRailEditProtected(prepared.baseRails.keys)
            val initialCount = rails.size
            val result = action()
            validatePreparedTopologyResult(prepared, initialCount)
            result
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

    private fun validatePreparedTopologyResult(prepared: PreparedRailTopologyUpdate, initialCount: Int) {
        check(rails.size == initialCount + prepared.expectedAddedRailCurves.keys.count { prepared.baseRails[it] == null } - prepared.expectedAbsentRailIds.size) { "Prepared topology rail count does not match committed edit" }
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
        prepared.expectedJoinedEndpoints.forEach { (endpoint, peers) ->
            check(endpointJoinedEndpoints(endpoint) == peers) { "Prepared topology endpoint group differs at $endpoint" }
        }
        prepared.expectedCrossings.forEach { (pair, expectedCrossing) ->
            check(atGradeRailCrossingIndex.crossing(pair) == expectedCrossing) {
                "Prepared topology crossing result differs for $pair"
            }
        }
    }

    private fun matchesPreparedTopologyBase(prepared: PreparedRailTopologyUpdate): Boolean {
        if (prepared.baseRails.any { (railId, expectedRail) -> rails[railId] !== expectedRail }) return false
        if (prepared.baseConnections.any { (endpoint, expectedTargets) ->
                rails[endpoint.railId] == null || endpointConnections(endpoint) != expectedTargets
            }
        ) return false
        if (prepared.baseJoinedEndpoints.any { (endpoint, peers) ->
                rails[endpoint.railId] == null || endpointJoinedEndpoints(endpoint) != peers
            }) return false
        if (prepared.baseCrossings.any { (pair, expectedCrossing) ->
                atGradeRailCrossingIndex.crossing(pair) != expectedCrossing
            }
        ) return false
        return true
    }

    private fun endpointJoinedEndpoints(endpoint: RailEndpointRef): Set<RailEndpointRef> {
        val rail = requireNotNull(rails[endpoint.railId]) { "Rail topology is missing endpoint $endpoint" }
        return (if (endpoint.endpoint == RailEndpoint.A) rail.viewBA else rail.viewAB).joinedEndpoints.toSet()
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
        listener: RailTopologyChangeListener
    ): AutoCloseable {
        railTopologyLock.read { topologyListeners.add(listener) }
        val closed = AtomicBoolean()
        return AutoCloseable { if (closed.compareAndSet(false, true)) topologyListeners.remove(listener) }
    }

    private fun notifyTopologyChange(change: RailTopologyChange) {
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

    private val railCleanupCollector = ThreadLocal<work.art1st.javarailways.core.api.util.DestructionSteps?>()

    /** Captures asynchronous cleanup initiated by one synchronous owner edit without waiting under locks. */
    fun <T> collectRailCleanup(action: () -> T): Pair<T, java.util.concurrent.CompletionStage<Void>> {
        check(railCleanupCollector.get() == null) { "Rail cleanup collection must not be nested" }
        val cleanup = work.art1st.javarailways.core.api.util.DestructionSteps()
        railCleanupCollector.set(cleanup)
        return try {
            action() to cleanup.finish()
        } catch (failure: Throwable) {
            cleanup.finish().whenComplete { _, cleanupFailure ->
                if (cleanupFailure != null) failure.addSuppressed(cleanupFailure)
            }
            throw failure
        } finally {
            railCleanupCollector.remove()
        }
    }

    /** Includes platform-owned rail cleanup in the current synchronous edit's completion result. */
    fun trackRailCleanup(result: java.util.concurrent.CompletionStage<Void>): java.util.concurrent.CompletionStage<Void> {
        railCleanupCollector.get()?.await { result }
        return result
    }

    @MainThread
    override fun removeRail(rail: Rail): java.util.concurrent.CompletionStage<Void> {
        require(rail.world === this) { "Rail ${rail.id} belongs to another world" }
        val removed = rail as? CoreRail ?: throw IllegalArgumentException("rail must be a CoreRail")
        val absent = railTopologyLock.read {
            val registered = rails[removed.id]
            require(registered == null || registered === removed) {
                "Rail ${removed.id} is not the registered instance"
            }
            registered == null
        }
        if (absent) return trackRailCleanup(removed.destroyResources())
        railTopologyTransaction {
            val registered = rails[removed.id]
            require(registered == null || registered === removed) {
                "Rail ${removed.id} is not the registered instance"
            }
            if (registered != null) removeRailInTransaction(removed)
        }
        return trackRailCleanup(removed.destroyResources())
    }

    private fun removeRailInTransaction(removed: CoreRail) {
        check(rails[removed.id] === removed)
        val connectedEndpointViews = sequenceOf(removed.viewAB, removed.viewBA)
            .flatMap { it.joinedEndpoints.asSequence() }
            .map { port ->
                val peer = requireNotNull(rails[port.railId]) { "Missing joined endpoint $port" }
                if (port.endpoint == RailEndpoint.A) peer.viewBA else peer.viewAB
            }
            .distinct()
            .toList()
        val affectedRailIds = buildSet {
            add(removed.id)
            connectedEndpointViews.mapTo(this) { it.rail.id }
        }
        ensureRailEditProtected(affectedRailIds)
        val occupyingTrainIds = occupyingTrainIds(affectedRailIds)
        require(occupyingTrainIds.isEmpty()) {
            "Cannot remove rail ${removed.id}; affected rails are occupied by ${occupyingTrainIds.sorted()}"
        }
        railTopologyTransactionContext.get()?.recordRailInventoryMutation(setOf(removed.id))
        connectedEndpointViews.forEach { view ->
            val removedWasActive = view.activeRail?.rail === removed
            if (removedWasActive) view.activeRail = null
            view.mutableNextRails.removeIf { it.rail === removed }
            view.mutableJoinedEndpoints.removeIf { it.railId == removed.id }
            if (removedWasActive) view.selectDefaultConnection()
        }
        removed.viewAB.activeRail = null
        removed.viewBA.activeRail = null
        removed.viewAB.mutableNextRails.clear()
        removed.viewBA.mutableNextRails.clear()
        removed.viewAB.mutableJoinedEndpoints.clear()
        removed.viewBA.mutableJoinedEndpoints.clear()
        atGradeRailCrossingIndex.remove(removed)
        rails.remove(removed.id)
    }

    /**
     * Publish one already-staged owner batch at the completed physical epoch, without a new step.
     * All candidates are prepared before installation. Mailbox frames and direction notifications
     * follow the single shared occupancy/world publication; installation failure stalls physics.
     */
    fun publishOwnerChanges(changedTrains: Collection<CoreTrain>) =
        publishMaintenanceChanges(changedTrains) {}

    internal fun publishMaintenanceChanges(changedTrains: Collection<CoreTrain>, beforeInstall: () -> Unit) {
        physicsCoordinator.ensureBetweenFrames("CoreWorld.publishOwnerChanges")
        val epoch = physicsCoordinator.captureCurrentTicket().epoch
        val changed = changedTrains.distinct()
        changed.forEach { train ->
            check(trains[train.id] === train && train.world === this) { "Owner batch contains a retired train" }
        }
        val prepared = changed.associateWith { it.prepareMaintenancePublication(epoch) }
        check(trains.values.all { it in prepared || it.publishedFrame.physicsEpoch == epoch }) {
            "Owner publication requires all unchanged train frames at completed epoch $epoch"
        }
        try {
            beforeInstall()
            prepared.forEach { (train, frame) -> train.applyMaintenancePublication(frame) }
            rebuildSweptOccupancyIndex(epoch)
            publishWorldFrame(epoch)
            changed.forEach { publishTrainFrame(it.publishedFrame) }
        } catch (failure: Throwable) {
            throw physicsCoordinator.stall("Owner train publication failed", failure)
        }
        changed.forEach(CoreTrain::notifyMaintenancePublication)
    }

    /** Refresh branch observations only when an owner operation actually changed selection. */
    fun publishOwnerSelectionChanges() {
        physicsCoordinator.ensureBetweenFrames("CoreWorld.publishOwnerSelectionChanges")
        val changed = synchronized(activeRailSelectionLock) {
            publishedWorldFrame.activeContinuations.selectionRevision != activeRailSelectionRevision
        }
        if (!changed) return
        try {
            publishOwnerChanges(emptyList())
        } catch (failure: Throwable) {
            throw physicsCoordinator.stall("Turnout selection publication failed", failure)
        }
    }

    /** Rebuilt by the physics Coordinator after all train states are published. */
    internal fun rebuildSweptOccupancyIndex(physicsEpoch: Long) {
        railTopologyLock.read {
            val frames = trains.values.map(CoreTrain::publishedFrame)
            val activeContinuations = captureActiveRailContinuations()
            val envelopes = trains.values.associate { train -> train.id to
                CollisionEnvelope.capture(this, train.publishedFrame,
                    train.cars, activeContinuations, previous = sweptOccupancy.collisionEnvelopes[train.id]) }
            sweptOccupancy = RailwayOccupancySnapshot.create(
                physicsEpoch, frames, envelopes, topologyRevision, activeContinuations
            )
        }
    }

    /** Low-frequency structural preparation; reuse the same immutable occupancy builder. */
    internal fun prepareCouplingOccupancy(
        master: CoreTrain, removed: CoreTrain, result: TrainFrameSnapshot,
        resultCars: List<CoreCar>,
        reversedCars: Set<CoreCar>
    ): RailwayOccupancySnapshot = railTopologyLock.read {
        val survivors = trains.values.filter { it !== master && it !== removed }
        val activeContinuations = captureActiveRailContinuations()
        val envelopes = survivors.associate { train -> train.id to
            (sweptOccupancy.collisionEnvelopes[train.id]?.takeIf { it.frame === train.publishedFrame }
                ?.takeIf { it.activeContinuations === activeContinuations }
                ?: CollisionEnvelope.capture(this, train.publishedFrame, train.cars, activeContinuations)) } +
            (master.id to CollisionEnvelope.capture(this, result, resultCars, activeContinuations, reversedCars))
        RailwayOccupancySnapshot.create(
            result.physicsEpoch, survivors.map(CoreTrain::publishedFrame) + result,
            envelopes, topologyRevision, activeContinuations
        )
    }

    /** Resolve a captured route continuation using the same immutable selection snapshot. */
    internal fun collisionContinuation(
        view: RailView,
        activeContinuations: ActiveRailContinuationsSnapshot
    ): RailView? {
        val entry = RailEndpointRef(view.rail.id,
            if (view.viewDirection == ViewDirection.AB) RailEndpoint.B else RailEndpoint.A)
        val topology = publishedTopologyState
        check(activeContinuations.topologyRevision == topology.revision) {
            "active continuation snapshot belongs to a stale topology"
        }
        val node = topology.structuralNodeAt(entry)
        val exit = when (node) {
            is TopologyNode.Turnout -> activeContinuations.selectedContinuation(entry)
            is TopologyNode.Terminus -> null
            null -> topology.ordinaryContinuationAt(entry)
            else -> node.ports.singleOrNull { it != entry }
        } ?: return null
        val rail = rails[exit.railId] ?: return null
        return if (exit.endpoint == RailEndpoint.A) rail.viewAB else rail.viewBA
    }

    /** Install a fully prepared collision index without rebuilding during structural commit. */
    internal fun applyPreparedOccupancy(occupancy: RailwayOccupancySnapshot) {
        sweptOccupancy = occupancy
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

    /** Internal diagnostic for proving that a concrete thread is waiting on the topology lock. */
    @JvmSynthetic
    internal fun isQueuedForRailTopologyAccess(thread: Thread): Boolean =
        railTopologyLock.hasQueuedThread(thread)

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
                        nextAtB = rail.viewAB.mutableNextRails.toList(),
                        joinedAtA = rail.viewBA.joinedEndpoints.toSet(),
                        joinedAtB = rail.viewAB.joinedEndpoints.toSet()
                    )
                }
        )

    private data class RailTopologyFingerprint(val rails: List<Entry>) {
        data class Entry(
            val rail: CoreRail,
            val nextAtA: List<RailView>,
            val nextAtB: List<RailView>,
            val joinedAtA: Set<RailEndpointRef>,
            val joinedAtB: Set<RailEndpointRef>
        )
    }

    private class RailTopologyTransactionContext(
        val before: RailTopologyFingerprint?,
        var allowedInventoryMutationRailIds: Set<UUID>?
    ) {
        var nestedFailure: Throwable? = null
            private set
        val railInventoryMutated: Boolean
            get() = railInventoryMutationCount > 0
        var railInventoryMutationCount: Int = 0
            private set
        val replacementRequirements = mutableListOf<ReplacementRequirement>()
        val editProtection = mutableListOf<AutoCloseable>()
        var allowedEditRailIds: Set<UUID>? = null

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

    internal fun querySweptCollision(train: CoreTrain): SweptOccupancyQueryResult = railTopologyLock.read {
        val occupancy = sweptOccupancy
        val own = occupancy.collisionEnvelopes[train.id]
        occupancy.queryFirstCollision(train.id, train.publishedSweptSpans, acceptConnector = { otherId ->
            val other = getTrain(otherId)
            val incoming = occupancy.collisionEnvelopes[otherId]
            other != null && own != null && incoming != null &&
                TrainCoupling.hasConnectorContact(train, other, own, incoming,
                    own.dependencies.matches(train) && incoming.dependencies.matches(other))
        })
    }

    internal fun collisionEnvelope(trainId: String): CollisionEnvelope? =
        sweptOccupancy.collisionEnvelopes[trainId]

    override fun inspectCouplingApproach(
        request: work.art1st.javarailways.core.api.physics.train.CouplingApproachRequest
    ): work.art1st.javarailways.core.api.physics.train.CouplingApproachInspection {
        physicsCoordinator.ensureBetweenFrames("CoreWorld.inspectCouplingApproach")
        return railTopologyLock.read { TrainCoupling.inspectApproach(this, request) }
    }

    override fun inspectCouplingModeEntry(
        approachingTrainId: String,
        stationaryTrainId: String,
        maximumGapMeters: Double
    ): work.art1st.javarailways.core.api.physics.train.CouplingApproachInspection {
        physicsCoordinator.ensureBetweenFrames("CoreWorld.inspectCouplingModeEntry")
        return railTopologyLock.read {
            TrainCoupling.inspectModeEntry(this, approachingTrainId, stationaryTrainId, maximumGapMeters)
        }
    }

    override fun completeCouplingContact(
        proof: work.art1st.javarailways.core.api.physics.train.CouplingApproachProof
    ): work.art1st.javarailways.core.api.physics.train.CouplingContactCompletion {
        physicsCoordinator.ensureBetweenFrames("CoreWorld.completeCouplingContact")
        return railTopologyLock.write { TrainCoupling.completeContact(this, proof) }
    }

    override fun validateCouplingApproach(
        proof: work.art1st.javarailways.core.api.physics.train.CouplingApproachProof
    ): Boolean {
        physicsCoordinator.ensureBetweenFrames("CoreWorld.validateCouplingApproach")
        return railTopologyLock.read { TrainCoupling.validateApproach(this, proof) }
    }

    override fun validateCouplingApproachGeometry(
        proof: work.art1st.javarailways.core.api.physics.train.CouplingApproachProof
    ): Boolean {
        physicsCoordinator.ensureBetweenFrames("CoreWorld.validateCouplingApproachGeometry")
        return railTopologyLock.read { TrainCoupling.validateApproachGeometry(this, proof) }
    }

    override fun validatePublishedCouplingApproach(
        proof: work.art1st.javarailways.core.api.physics.train.CouplingApproachProof
    ): Boolean = TrainCoupling.validatePublishedApproach(this, proof)

    override fun clampCouplingApproachDistance(
        proof: work.art1st.javarailways.core.api.physics.train.CouplingApproachProof,
        requestedDistanceMeters: Double
    ): Double {
        work.art1st.javarailways.core.api.threading.ThreadGuard.ensureAsyncTickThread("CoreWorld.clampCouplingApproachDistance")
        return TrainCoupling.clampApproachDistance(this, proof, requestedDistanceMeters)
    }

    override fun releaseFormationMovementHold(
        hold: work.art1st.javarailways.core.api.physics.train.FormationMovementHold
    ): Boolean {
        physicsCoordinator.ensureBetweenFrames("CoreWorld.releaseFormationMovementHold")
        val train = getTrain(hold.trainId) ?: return false
        return train.releaseFormationMovementHold(hold)
    }

    override fun restoreFormationMovementHold(
        trainId: String,
        expectedCarIds: List<String>
    ): work.art1st.javarailways.core.api.physics.train.FormationMovementHoldRestoration {
        physicsCoordinator.ensureBetweenFrames("CoreWorld.restoreFormationMovementHold")
        val train = getTrain(trainId)
        val frame = train?.publishedFrame
        return if (expectedCarIds.isEmpty() || expectedCarIds.any(String::isBlank) ||
            expectedCarIds.distinct().size != expectedCarIds.size || train == null ||
            train.cars.map { it.id } != expectedCarIds ||
            frame == null || frame.cars.map { it.carId } != expectedCarIds ||
            frame.reversed != train.isReversed || frame.derailed || train.isDerailed ||
            frame.speedMetersPerSecond != 0.0 ||
            (frame.headTraversal + frame.tailTraversal).any {
                it.startArcLengthInAbMeters != it.endArcLengthInAbMeters
            } || frame.cars.any { car -> (car.bogies + car.state).any {
                it.velocityX != 0.0 || it.velocityY != 0.0 || it.velocityZ != 0.0
            } }
        ) work.art1st.javarailways.core.api.physics.train.FormationMovementHoldRestoration.Rejected(
            "recovery requires an exact stopped current formation")
        else work.art1st.javarailways.core.api.physics.train.FormationMovementHoldRestoration.Accepted(
            train.restoreFormationMovementHold())
    }

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
        val physicsCoordinator: PhysicsCoordinator = PhysicsCoordinator(1),
        val executionMode: WorldExecutionMode = WorldExecutionMode.LIVE
    )
}
