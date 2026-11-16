package work.art1st.javarailways.core.physics.train

import java.util.concurrent.CompletionStage
import work.art1st.javarailways.core.api.util.Destruction
import work.art1st.javarailways.core.api.util.DestructionSteps
import org.jetbrains.annotations.ApiStatus
import org.joml.times
import org.joml.Vector3d
import work.art1st.javarailways.core.api.control.PreparedControllerStateUpdate
import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.api.event.DerailEvent
import work.art1st.javarailways.core.api.event.processor.MainRailEventBus
import work.art1st.javarailways.core.api.event.processor.PhysicsRailEventBus
import work.art1st.javarailways.core.api.event.PassSignalEvent
import work.art1st.javarailways.core.api.event.TrainSwitchDirectionEvent
import work.art1st.javarailways.core.api.event.TrainSplitEvent
import work.art1st.javarailways.core.api.event.TrainSplitSuccessEvent
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.exception.CannotCreateTrainException
import work.art1st.javarailways.core.api.physics.exception.CannotMoveForwardException
import work.art1st.javarailways.core.api.physics.exception.PhysicsException
import work.art1st.javarailways.core.api.physics.rail.RailLocation
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.train.CarFrameSnapshot
import work.art1st.javarailways.core.api.physics.train.RailTraversalSegmentSnapshot
import work.art1st.javarailways.core.api.physics.train.SweptRailSpanSnapshot
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.physics.train.TrainExtension
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.api.render.RenderableFrameSnapshot
import work.art1st.javarailways.core.api.render.Renderable
import work.art1st.javarailways.core.api.util.annotation.MainThread
import work.art1st.javarailways.core.api.util.annotation.AsyncEventApi
import work.art1st.javarailways.core.api.util.plugin.Extendable
import work.art1st.javarailways.core.api.util.plugin.ExtensionData
import work.art1st.javarailways.core.api.util.plugin.ExtensionDataContainer
import work.art1st.javarailways.core.api.util.plugin.collectSavedExtensionData
import work.art1st.javarailways.core.api.util.plugin.SerializableExtensionData
import work.art1st.javarailways.core.api.util.tick.Synchronizable
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.rail.RailSegment
import work.art1st.javarailways.core.physics.rail.RailView
import work.art1st.javarailways.core.physics.rail.SweptRailSpan
import work.art1st.javarailways.core.api.util.Destroyable
import work.art1st.configLib.persistence.PersistentBy
import work.art1st.javarailways.core.util.CommitBuffer
import work.art1st.javarailways.core.api.threading.ThreadGuard
import java.util.concurrent.Future
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.train.TrainComposition

/**
 * 列车类
 * 由若干节车厢组成，车厢之间头尾相连
 */
open class CoreTrain @MainThread internal constructor(
    override val id: String,
    cars: List<CoreCar>,
    override val world: CoreWorld,
    savedExtensionData: Map<String, SerializableExtensionData>,
    initialName: String = id,
    val extensionDataContainer: ExtensionDataContainer<TrainExtension> = ExtensionDataContainer(),
    private val persistedHeadLocation: RailLocationWithFacing? = null,
    private val persistedTailLocation: RailLocationWithFacing? = null,
    initialReversed: Boolean = false,
    initialComposition: TrainComposition? = null,
    transferringCompositionFrom: CoreTrain? = null,
) : Train, Destroyable, Synchronizable, Extendable<TrainExtension> by extensionDataContainer, PersistentBy<SavedTrainV1> {
    private var structuralComposition = initialComposition?.let {
        TrainComposition.create(it.units, cars.map { car -> car.id })
    } ?: TrainComposition.single(cars.map { it.id }, id)

    override val composition: TrainComposition get() = publishedFrameRef.get()?.composition ?: structuralComposition

    /** A split suffix reserves its train identity before extension initialization, but is not committed yet. */
    @Volatile
    internal var compositionTransferPending: Boolean = transferringCompositionFrom != null
        private set

    /** Process-local implementation identity for render handoff; not persistence or `core.api`. */
    val publicationStreamId: UUID = UUID.randomUUID()

    @Volatile
    private var _name: String = validateTrainName(initialName)
    override val name: String
        get() = _name

    internal fun rename(newName: String) {
        _name = validateTrainName(newName)
    }

    @Volatile
    private var _controller: TrainController? = null
    final override val controller: TrainController?
        get() = _controller
    @Volatile
    var movementHeld: Boolean = false
        private set
    private var configurationMovementHeld = false
    private var formationMovementHold: work.art1st.javarailways.core.api.physics.train.FormationMovementHold? = null

    /** Read-only structural fence state; this exposes no authority to release the fence. */
    val hasFormationMovementHold: Boolean get() = formationMovementHold != null

    internal val canPrepareWhileFormationHeld: Boolean
        get() = formationMovementHold != null && !configurationMovementHeld && !isDerailed

    /** Quarantine movement without removing the train or its physical occupancy. */
    fun setMovementHeld(held: Boolean) {
        world.physicsCoordinator.ensureBetweenFrames("CoreTrain.setMovementHeld")
        check(world.getTrain(id) === this) { "Cannot hold a retired train '$id'" }
        configurationMovementHeld = held
        movementHeld = held || formationMovementHold != null
    }

    /** Commit-only owner installation, before the resulting world frame can be observed. */
    internal fun installFormationMovementHold(): work.art1st.javarailways.core.api.physics.train.FormationMovementHold {
        check(formationMovementHold == null) { "formation output already has a movement fence" }
        return work.art1st.javarailways.core.api.physics.train.FormationMovementHold(id, this).also {
            formationMovementHold = it
            movementHeld = true
        }
    }

    internal fun releaseFormationMovementHold(
        hold: work.art1st.javarailways.core.api.physics.train.FormationMovementHold
    ): Boolean {
        if (hold.source !== this || formationMovementHold !== hold) return false
        formationMovementHold = null
        movementHeld = configurationMovementHeld
        return true
    }

    internal fun restoreFormationMovementHold(): work.art1st.javarailways.core.api.physics.train.FormationMovementHold =
        formationMovementHold ?: installFormationMovementHold()

    override var cars: List<CoreCar> = cars
        protected set
    private data class DerailState(val derailed: Boolean, val reason: String?)

    private val derailStateBuffer = CommitBuffer(DerailState(false, null))
    private val derailStateLock = Any()
    private var derailPhysicsDirty = false
    private var removeControllerOnPhysicsPublish = false
    private var pendingDerailNotificationReason: String? = null
    private val activeDerailState: DerailState
        get() = if (derailPhysicsDirty) derailStateBuffer.getActive() else derailStateBuffer.get()

    /** Coordinator-only read before workers start; includes prior decisions not yet published. */
    internal val isDerailedForPhysics: Boolean get() = activeDerailState.derailed

    @Volatile
    internal var publishedSweptSpans: List<SweptRailSpan> = emptyList()
        private set
    @Volatile
    private var publishedPlacementIntervals: List<RailInterval> = emptyList()
    @Volatile
    private var publishedBodyTraversal: List<RailTraversalSegmentSnapshot> = emptyList()
    private var pendingSweptSpans: List<SweptRailSpan>? = null
    private var publishedHeadTraversal: List<RailTraversalSegmentSnapshot> = emptyList()
    private var publishedTailTraversal: List<RailTraversalSegmentSnapshot> = emptyList()
    private var pendingHeadTraversal: List<RailTraversalSegmentSnapshot>? = null
    private var pendingTailTraversal: List<RailTraversalSegmentSnapshot>? = null
    /** Invalidates read-only physical proofs before any structural/configuration publication. */
    @Volatile
    internal var formationGeometryIdentity: Any = Any()
        private set

    private val frameRevision = AtomicLong(0L)
    private val publishedFrameRef = AtomicReference<TrainFrameSnapshot?>()
    private var stagedPlacementDirection: Boolean? = null

    /** Latest complete value-only frame; consumers must not supplement it from live train state. */
    override val publishedFrame: TrainFrameSnapshot
        get() = publishedFrameRef.get() ?: error("initial train frame has not been published: $id")

    /** Opaque state used to roll back an administrator maintenance relocation. */
    class PlacementSnapshot internal constructor(
        internal val trainId: String,
        internal val headLocation: RailLocationWithFacing,
        internal val tailLocation: RailLocationWithFacing,
        internal val reversed: Boolean,
        internal val derailed: Boolean,
        internal val derailReason: String?,
        internal val frame: TrainFrameSnapshot,
        internal val carStates: List<CoreCar.PlacementState>
    )
    override val isDerailed: Boolean
        get() = derailStateBuffer.get().derailed
    override val derailReason: String?
        get() = derailStateBuffer.get().reason

    private val reversedBuffer = CommitBuffer(initialReversed)
    override var isReversed: Boolean
        get() = reversedBuffer.get()
        protected set(value) {
            reversedBuffer.set(value)
            reversedBuffer.commit()
            reversedBuffer.set(value)
        }
    private val activeIsReversed: Boolean
        get() = isReversed
    override val length
        get() = cars.size
    var head: TrainEnd
        protected set
    val tail: TrainEnd
    override val headLocation: RailLocation
        get() = head.location
    override val tailLocation: RailLocation
        get() = tail.location
    override val occupiedRailIntervals: List<RailInterval>
        get() = publishedPlacementIntervals

    init {
        world.physicsCoordinator.ensureBetweenFrames("CoreTrain construction")
        world.validateNewTrainIdentity(id, name)
        world.validateNewTrainComposition(structuralComposition, transferringCompositionFrom)
        val initialHeadLocation = persistedHeadLocation
            ?: headBogie.location.forward(headCar.headBogieToEndPointOffset)
            ?: throw CannotCreateTrainException(headBogie.location.let { anchor ->
                "Cannot place train HEAD endpoint: train=$id, anchorRail=${anchor.rail.id}, " +
                    "anchorFacing=${anchor.facing}, anchorArcMeters=${anchor.arcLength}, " +
                    "endOffsetMeters=${headCar.headBogieToEndPointOffset}"
            })
        val initialTailLocation = persistedTailLocation
            ?: tailBogie.location.forward(-tailCar.tailBogieToEndPointOffset)
            ?: throw CannotCreateTrainException(tailBogie.location.let { anchor ->
                "Cannot place train TAIL endpoint: train=$id, anchorRail=${anchor.rail.id}, " +
                    "anchorFacing=${anchor.facing}, anchorArcMeters=${anchor.arcLength}, " +
                    "endOffsetMeters=${-tailCar.tailBogieToEndPointOffset}"
            })
        var createdHead: TrainEnd? = null
        var createdTail: TrainEnd? = null
        var attachedToWorld = false
        try {
            head = TrainEnd(initialHeadLocation, this, TrainEnd.Type.HEAD).also { createdHead = it }
            tail = TrainEnd(initialTailLocation, this, TrainEnd.Type.TAIL).also { createdTail = it }
            world.addTrain(this)
            attachedToWorld = true
            extensionDataContainer.extensionData.forEach { it?.initialize(this, savedExtensionData) }
            publishedSweptSpans = spansFromLocations(currentRailLocations())
            publishedPlacementIntervals = intervalsFromSpans(publishedSweptSpans)
            publishedBodyTraversal = try {
                traversalSnapshots(placementSegments(tail.location, head.location))
            } catch (_: IllegalStateException) {
                // Command-style construction may relocate an initially unaligned consist immediately.
                // Such an initial frame is not admissible; the first committed placement is strict.
                emptyList()
            }
            publishedFrameRef.set(captureFrame(
                revision = 0L, physicsEpoch = world.physicsCoordinator.captureCurrentTicket().epoch
            ))
            cars.forEach { it.transferPublicationOwnership(this) }
        } catch (failure: Throwable) {
            // Only this constructor's registrations are owned here; its caller still owns the cars.
            val cleanup = DestructionSteps()
            cleanup.action { if (attachedToWorld) removeController() }
            createdHead?.let { end -> cleanup.await { end.destroy() } }
            createdTail?.let { end -> cleanup.await { end.destroy() } }
            if (attachedToWorld) cleanup.action { world.unregisterTrain(this) }
            cleanup.finish().whenComplete { _, cleanupFailure ->
                if (cleanupFailure != null) failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    val headCar
        get() = if (isReversed) cars.last() else cars.first()
    val tailCar
        get() = if (isReversed) cars.first() else cars.last()
    val headBogie: CoreBogie
        get() = headCar.headBogie
    val tailBogie: CoreBogie
        get() = tailCar.tailBogie
    private val activeHeadCar: CoreCar
        get() = if (activeIsReversed) cars.last() else cars.first()
    private val activeTailCar: CoreCar
        get() = if (activeIsReversed) cars.first() else cars.last()
    private val activeHeadBogie: CoreBogie
        get() = activeHeadCar.activeHeadBogie
    private val activeTailBogie: CoreBogie
        get() = activeTailCar.activeTailBogie
    /* >=0 */
    override val speed: Double
        get() = if (movementHeld) 0.0 else controller?.speed ?: 0.0

    /**
     * 将列车向前移动 delta S
     * 移动第一个转向架，然后更新整列车的位置和车厢状态
     * 
     * @param deltaS 移动的弧长距离
     * @throws IllegalArgumentException 如果输入参数无效
     * @throws IllegalStateException 如果列车没有转向架
     */
    @OptIn(AsyncEventApi::class)
    override fun moveTrainForward(deltaS: Double) {
        moveTrainForwardFromController(deltaS, 0.0)
    }

    /**
     * Moves from a controller tick whose post-command speed is known in metres per second.
     * This implementation seam keeps the public distance-only API from inventing a velocity.
     */
    @OptIn(AsyncEventApi::class)
    internal fun moveTrainForwardFromController(deltaS: Double, speedMetersPerSecond: Double) {
        require(speedMetersPerSecond.isFinite() && speedMetersPerSecond >= 0.0) {
            "speedMetersPerSecond must be finite and non-negative"
        }
        require(deltaS.isFinite() && deltaS >= 0.0) { "deltaS must be finite and non-negative" }
        if (activeDerailState.derailed || movementHeld) {
            updateActiveMotion(0.0)
            return
        }

        if (deltaS == 0.0) {
            updateActiveMotion(speedMetersPerSecond)
            pendingSweptSpans = spansFromLocations(currentRailLocations())
            pendingHeadTraversal = emptyList()
            pendingTailTraversal = emptyList()
            return
        }

        val signals = head.location.peekSignals(deltaS)

        try {
            val previousLocations = currentRailLocations()
            val traversedSegments = head.location.peekCurve(deltaS).toList()
            headBogie.moveForward(deltaS)
            val tailTraversal = updateAfterHeadBogie(deltaS)
            updateActiveMotion(speedMetersPerSecond)
            pendingSweptSpans = mergeSweptSpans(
                spansFromLocations(previousLocations + activeRailLocations()) +
                    traversedSegments
                        .filter { kotlin.math.abs(it.endArcLength - it.startArcLength) > Curve.TOLERANCE }
                        .map(::spanFromSegment)
            )
            pendingHeadTraversal = traversalSnapshots(traversedSegments)
            pendingTailTraversal = traversalSnapshots(tailTraversal)
        } catch (e: PhysicsException) {
            try {
                cars.forEach(CoreCar::discardPhysicsChanges)
                head.discardPhysicsChanges()
                tail.discardPhysicsChanges()
                pendingSweptSpans = publishedPlacementIntervals.map { interval ->
                    SweptRailSpan(id, interval.railId,
                        interval.minArcLengthInAbMeters, interval.maxArcLengthInAbMeters)
                }
                pendingHeadTraversal = emptyList()
                pendingTailTraversal = emptyList()
                updateActiveMotion(0.0)
                markDerailedFromPhysics("Train movement failed: train=$id, deltaSMeters=$deltaS: $e")
            } catch (rollbackFailure: Throwable) {
                rollbackFailure.addSuppressed(e)
                throw world.physicsCoordinator.stall("Failed to isolate train movement: train=$id", rollbackFailure)
            }
            java.util.logging.Logger.getLogger(CoreTrain::class.java.name).log(
                java.util.logging.Level.SEVERE, "Train movement failed: train=$id, deltaSMeters=$deltaS", e
            )
            return
        }
        // Only completed movement can produce crossing requests. Observer failures cannot
        // be isolated by rolling back this train's writable geometry.
        signals.forEach { PhysicsRailEventBus.publish(PassSignalEvent(this, it)) }
    }

    /** Propagates an authoritative scalar speed along the active train-local path. */
    private fun updateActiveMotion(speedMetersPerSecond: Double) {
        if (speedMetersPerSecond == 0.0) {
            cars.forEach(CoreCar::clearMotionState)
            return
        }
        val orderedCars = if (activeIsReversed) cars.asReversed() else cars
        var previousCar = orderedCars.first()
        previousCar.updateMotionStateByHeadVelocity(
            previousCar.activeHeadBogie.activeLocation.tangent * speedMetersPerSecond
        )
        for (car in orderedCars.drop(1)) {
            car.updateMotionStateByHeadVelocity(previousCar.activeTailVelocity())
            previousCar = car
        }
    }

    /** Clears the complete active consist motion when the coordinator suppresses movement. */
    internal fun clearActiveMotionFromPhysics() {
        updateActiveMotion(0.0)
    }

    @Throws(PhysicsException::class)
    fun setLocation(headLocation: RailLocationWithFacing) {
        world.physicsCoordinator.ensureBetweenFrames("CoreTrain.setLocation")
        check(world.getTrain(id) === this) { "Cannot place a retired train '$id'" }
        val previousHeadLocation = head.location
        val previousLocations = currentRailLocations()
        val previousPendingSweptSpans = pendingSweptSpans
        val wasDerailed = isDerailed
        val previousDerailReason = derailReason
        val wasReversed = isReversed
        val directionChanged = isReversed
        val previousStagedDirection = stagedPlacementDirection
        try {
            if (stagedPlacementDirection == null) stagedPlacementDirection = wasReversed
            clearDerailed()
            // 正面面朝此location；成功后才发布方向事件。
            if (directionChanged) reverseOnMainThread()
            applyHeadLocation(headLocation)
            pendingSweptSpans = spansFromLocations(previousLocations + activeRailLocations())
        } catch (failure: Exception) {
            runCatching {
                if (isReversed != wasReversed) reverseOnMainThread()
                applyHeadLocation(previousHeadLocation)
                pendingSweptSpans = previousPendingSweptSpans
            }.exceptionOrNull()?.let(failure::addSuppressed)
            publishDerailState(DerailState(wasDerailed, previousDerailReason))
            stagedPlacementDirection = previousStagedDirection
            throw failure
        }
    }

    /** Capture a stable maintenance rollback point after the current physics epoch is published. */
    @MainThread
    fun capturePlacementSnapshot(): PlacementSnapshot {
        world.physicsCoordinator.ensureBetweenFrames("CoreTrain.capturePlacementSnapshot")
        check(pendingSweptSpans == null) {
            "cannot capture placement while train physics state is pending: $id"
        }
        return PlacementSnapshot(
            trainId = id,
            headLocation = head.location,
            tailLocation = tail.location,
            reversed = isReversed,
            derailed = isDerailed,
            derailReason = derailReason,
            frame = publishedFrame,
            carStates = cars.map(CoreCar::capturePlacementState)
        )
    }

    /** Restore an unpublished maintenance attempt exactly, retaining the original immutable frame. */
    @MainThread
    fun restorePlacementSnapshot(snapshot: PlacementSnapshot) {
        world.physicsCoordinator.ensureBetweenFrames("CoreTrain.restorePlacementSnapshot")
        require(snapshot.trainId == id) { "placement snapshot belongs to ${snapshot.trainId}, not $id" }
        check(publishedFrame === snapshot.frame) { "Cannot roll back a placement after publication: $id" }
        check(cars.size == snapshot.carStates.size) { "Placement consist changed: $id" }
        cars.zip(snapshot.carStates).forEach { (car, state) -> car.restorePlacementState(state) }
        isReversed = snapshot.reversed
        head.location = snapshot.headLocation
        tail.location = snapshot.tailLocation
        head.publishPhysicsState()
        tail.publishPhysicsState()
        publishDerailState(DerailState(snapshot.derailed, snapshot.derailReason))
        pendingSweptSpans = null
        pendingHeadTraversal = null
        pendingTailTraversal = null
        stagedPlacementDirection = null
    }

    /** Publish a validated maintenance placement without retaining its teleport sweep. */
    @MainThread
    fun commitMaintenancePlacement() {
        world.publishOwnerChanges(listOf(this))
    }

    internal data class MaintenancePublication(val frame: TrainFrameSnapshot, val spans: List<SweptRailSpan>)

    internal fun prepareMaintenancePublication(epoch: Long): MaintenancePublication {
        val segments = placementSegments(tail.activeLocation, head.activeLocation)
        val spans = mergeSweptSpans(segments.map(::spanFromSegment))
        return MaintenancePublication(captureFrame(
            revision = frameRevision.get() + 1L, physicsEpoch = epoch,
            frameHead = head.activeLocation, frameTail = tail.activeLocation,
            framePlacementIntervals = intervalsFromSpans(spans), frameBodyTraversal = traversalSnapshots(segments),
            frameSweptSpans = spans, frameHeadTraversal = emptyList(), frameTailTraversal = emptyList(),
            preparedCars = cars.associateWith(CoreCar::captureActivePlacementFrame)
        ), spans)
    }

    internal fun applyMaintenancePublication(prepared: MaintenancePublication) {
        updateActiveMotion(0.0)
        pendingSweptSpans = prepared.spans
        publishPhysicsState(refreshPlacement = false)
        publishedPlacementIntervals = prepared.frame.occupiedIntervals
        publishedBodyTraversal = prepared.frame.bodyTraversal
        frameRevision.set(prepared.frame.revision)
        publishedFrameRef.set(prepared.frame)
    }

    internal fun notifyMaintenancePublication() {
        val previousDirection = stagedPlacementDirection
        stagedPlacementDirection = null
        if (previousDirection != null && previousDirection != isReversed) {
            MainRailEventBus.publish(TrainSwitchDirectionEvent(this))
        }
    }

    /** Exact rail intervals occupied by the currently active placement from tail to head. */
    @MainThread
    fun activePlacementEnvelope(): List<SweptRailSpan> {
        val spans = placementSegments(tail.activeLocation, head.activeLocation).map(::spanFromSegment)
        return mergeSweptSpans(spans)
    }

    /** Ordered train-local placement from [from] to [to], following the currently selected path. */
    internal fun placementSegments(
        from: RailLocationWithFacing,
        to: RailLocationWithFacing
    ): List<RailSegment> {
        var view = from.railView
        var startArc = from.arcLength
        val segments = ArrayList<RailSegment>()
        val visited = HashSet<Pair<java.util.UUID, ViewDirection>>()
        while (true) {
            val curveLength = view.curve.totalArcLength
            if (view === to.railView && to.arcLength + Curve.TOLERANCE >= startArc) {
                segments += RailSegment(view, startArc, to.arcLength.coerceAtLeast(startArc))
                return segments
            }
            val key = view.rail.id to view.viewDirection
            check(visited.add(key)) { "train placement loops before reaching its head: $id" }
            segments += RailSegment(view, startArc, curveLength)
            view = view.activeRail
                ?: error("train placement is disconnected between tail and head: $id")
            startArc = 0.0
        }
    }

    private fun applyHeadLocation(headLocation: RailLocationWithFacing) {
        headBogie.updateLocation(
            headLocation.forward(-headCar.headBogieToEndPointOffset)
                ?: throw CannotMoveForwardException(this)
        )
        // Explicit placement is distance-only. Clear the prior controller epoch before solving
        // the rest of the consist, because the geometry solver reads the active head velocity.
        updateActiveMotion(0.0)
        updateAfterHeadBogie()
    }

    /** Validate the saved path before rebuilding state without moving exact persisted bogies. */
    internal fun restorePersistedPlacement(reversed: Boolean) {
        val savedHead = requireNotNull(persistedHeadLocation) {
            "persisted train $id is missing its head location"
        }
        val savedTail = requireNotNull(persistedTailLocation) {
            "persisted train $id is missing its tail location"
        }
        validatePersistedPlacementPath(savedTail, savedHead, reversed)
        isReversed = reversed
        cars.forEach { it.restorePersistedState(it.isReversed) }
        updateActiveMotion(0.0)
        head.location = savedHead
        tail.location = savedTail
        pendingSweptSpans = spansFromLocations(activeRailLocations())
    }

    /**
     * Use the same tail-to-head active path as body publication. Backward selection at a turnout
     * can differ from the occupied forward path. World positions alone cannot prove rail identity
     * or facing. Arc distance proves order and train-end offsets; the real 3D connector chord
     * proves axle spacing and the consistently head-anchored centres prove adjacent couplers.
     */
    private fun validatePersistedPlacementPath(
        savedTail: RailLocationWithFacing,
        savedHead: RailLocationWithFacing,
        reversed: Boolean
    ) {
        val segments = placementSegments(savedTail, savedHead)
        val pathLength = segments.sumOf { it.totalArcLength }
        var segmentIndex = 0
        var precedingLength = 0.0
        var previousDistance = 0.0

        fun distanceAlongPath(location: RailLocationWithFacing): Double {
            while (segmentIndex < segments.size) {
                val segment = segments[segmentIndex]
                if (segment.railView === location.railView &&
                    location.arcLength >= segment.startArcLength - Curve.TOLERANCE &&
                    location.arcLength <= segment.endArcLength + Curve.TOLERANCE
                ) {
                    val distance = precedingLength + location.arcLength - segment.startArcLength
                    check(distance + Curve.TOLERANCE >= previousDistance) {
                        "persisted train $id has bogies out of order on its tail-to-head path"
                    }
                    previousDistance = distance
                    return distance
                }
                precedingLength += segment.totalArcLength
                segmentIndex++
            }
            error("persisted train $id has a bogie outside its tail-to-head path or facing")
        }

        val tailToHeadCars = if (reversed) cars else cars.asReversed()
        var followingHead: Vector3d? = null
        var followingDirection: Vector3d? = null
        var followingConnectorLength = 0.0
        tailToHeadCars.forEachIndexed { index, car ->
            val carReversed = car.isReversed
            val tailBogie = car.tailBogie
            val headBogie = car.headBogie
            val tailDistance = distanceAlongPath(tailBogie.location)
            val headDistance = distanceAlongPath(headBogie.location)
            val configuredSpacing = car.bogieAOffset - car.bogieBOffset
            val headPoint = Vector3d(headBogie.location.position)
                .add(Vector3d(headBogie.location.normal).mul(car.floorHeight))
            val tailPoint = Vector3d(tailBogie.location.position)
                .add(Vector3d(tailBogie.location.normal).mul(car.floorHeight))
            val spacing = headPoint.distance(tailPoint)
            check(spacing.isFinite() && spacing > 0.0 &&
                kotlin.math.abs(spacing - configuredSpacing) <= Curve.TOLERANCE) {
                "persisted train $id car ${car.id} has inconsistent bogie connector spacing"
            }
            val direction = Vector3d(headPoint).sub(tailPoint).normalize()
            val headOffset = if (carReversed) -car.bogieBOffset else car.bogieAOffset
            val centre = Vector3d(headPoint).sub(Vector3d(direction).mul(headOffset))
            followingHead?.let { behindHead ->
                val gap = Vector3d(centre).sub(Vector3d(direction).mul(car.length / 2.0)).sub(behindHead)
                val connectorLength = followingConnectorLength +
                    car.tailConnectorLength
                check(gap.length().isFinite() &&
                    kotlin.math.abs(gap.length() - connectorLength) <= Curve.TOLERANCE &&
                    gap.dot(requireNotNull(followingDirection)) >= -Curve.TOLERANCE) {
                    "persisted train $id car ${car.id} has inconsistent adjacent coupler geometry"
                }
            }
            followingHead = Vector3d(centre).add(Vector3d(direction).mul(car.length / 2.0))
            followingDirection = direction
            followingConnectorLength = car.headConnectorLength
            if (index == 0) {
                val tailOffset = car.tailBogieToEndPointOffset
                check(kotlin.math.abs(tailDistance - tailOffset) <= Curve.TOLERANCE) {
                    "persisted train $id has an inconsistent tail endpoint offset"
                }
            }
            if (index == tailToHeadCars.lastIndex) {
                val headOffset = car.headBogieToEndPointOffset
                check(kotlin.math.abs(pathLength - headDistance - headOffset) <= Curve.TOLERANCE) {
                    "persisted train $id has an inconsistent head endpoint offset"
                }
            }
        }
    }

    /**
     * Removes constructor side effects when persisted placement validation fails.
     *
     * Every owner-side registration is revoked before return; platform cleanup completion and
     * failures are propagated asynchronously without blocking the owner.
     */
    internal fun discardFailedLoad(): CompletionStage<Void> = destruction.run {
        world.physicsCoordinator.ensureBetweenFrames("CoreTrain.discardFailedLoad")
        val steps = DestructionSteps()
        steps.action { removeController() }
        steps.await { head.destroy() }
        steps.await { tail.destroy() }
        cars.forEach { car -> steps.await { car.destroyOwnedResources() } }
        steps.action { world.unregisterTrain(this) }
        steps.finish()
    }

    private fun updateAfterHeadBogie(deltaS: Double? = null): List<RailSegment> {
        val orderedCars = if (activeIsReversed) cars.asReversed() else cars
        var prevCar = orderedCars.first()
        prevCar.updateByHeadBogie(activeHeadBogie.activeLocation, deltaS)
        for (car in orderedCars.drop(1)) {
            car.updateByPrevCar(prevCar, deltaS)
            prevCar = car
        }
        head.location = activeHeadBogie.activeLocation.forward(
            activeHeadCar.activeHeadBogieToEndPointOffset
        ) ?: throw CannotMoveForwardException(this)
        val tailAdvance = if (deltaS != null) {
            findTailEndForward(tail.activeLocation, activeTailBogie.activeLocation, deltaS)
        } else {
            TailEndAdvance(
                activeTailBogie.activeLocation.forward(-activeTailCar.activeTailBogieToEndPointOffset)
                    ?: throw CannotMoveForwardException(this),
                emptyList()
            )
        }
        tail.location = tailAdvance.location
        return tailAdvance.traversal
    }

    private data class TailEndAdvance(
        val location: RailLocationWithFacing,
        val traversal: List<RailSegment>
    )

    /**
     * Re-anchor the tail end from the train-local path used in this tick.
     *
     * Searching forward from the previous tail is intentional: looking backwards from the solved
     * tail bogie would consult the independently selected reverse RailView at a trailing turnout.
     * The root is the point whose forward rail distance to the solved bogie equals the configured
     * bogie-to-end offset, so the endpoint is solved afresh instead of accumulating head deltaS.
     */
    private fun findTailEndForward(
        previousTail: RailLocationWithFacing,
        solvedTailBogie: RailLocationWithFacing,
        deltaS: Double
    ): TailEndAdvance {
        val endOffset = activeTailCar.activeTailBogieToEndPointOffset
        val maximumBogieDistance = deltaS + endOffset + TAIL_END_SEARCH_MARGIN_METERS
        val bogieDistance = forwardDistanceTo(previousTail, solvedTailBogie, maximumBogieDistance)
            ?: throw CannotMoveForwardException(
                "tail bogie is not reachable on the train-local forward path: train=$id " +
                    "tail=${previousTail.rail.id}/${previousTail.facing}@${previousTail.arcLength} " +
                    "bogie=${solvedTailBogie.rail.id}/${solvedTailBogie.facing}@${solvedTailBogie.arcLength} " +
                    "maximum=$maximumBogieDistance"
            )
        if (bogieDistance + Curve.TOLERANCE < endOffset) {
            throw CannotMoveForwardException(
                "solved tail bogie lies behind the tail endpoint offset: train=$id " +
                    "distance=$bogieDistance offset=$endOffset"
            )
        }
        val endpointSearchDistance = (bogieDistance - endOffset).coerceAtLeast(0.0)
        val traversal = previousTail.peekCurve(endpointSearchDistance)
        val location = traversal.searchRailLocation(Curve.TOLERANCE) { candidate ->
            val candidateDistance = forwardDistanceTo(previousTail, candidate, endpointSearchDistance)
                ?: throw CannotMoveForwardException("tail bisection left its train-local search path: train=$id")
            bogieDistance - candidateDistance - endOffset
        } ?: throw CannotMoveForwardException(
            "tail bisection did not converge: train=$id distance=$bogieDistance offset=$endOffset"
        )
        return TailEndAdvance(location, traversal.toList())
    }

    private fun forwardDistanceTo(
        from: RailLocationWithFacing,
        target: RailLocationWithFacing,
        maximumDistance: Double
    ): Double? {
        var traversed = 0.0
        for (segment in from.peekCurve(maximumDistance)) {
            if (segment.railView === target.railView &&
                target.arcLength >= segment.startArcLength - Curve.TOLERANCE &&
                target.arcLength <= segment.endArcLength + Curve.TOLERANCE
            ) {
                return traversed + (target.arcLength - segment.startArcLength)
            }
            traversed += segment.totalArcLength
        }
        return null
    }

    @MainThread
    override fun reverse() {
        ThreadGuard.ensureMainThread("CoreTrain.reverse")
        world.physicsCoordinator.withStructuralMutation {
            check(world.getTrain(id) === this) { "Cannot reverse a retired train '$id'" }
            val epoch = world.physicsCoordinator.captureCurrentTicket().epoch
            check(world.allTrains.all { it.publishedFrame.physicsEpoch == epoch }) {
                "Cannot reverse before all train frames belong to completed epoch $epoch"
            }
            val geometry = PreparedTrainGeometry(
                cars, head.location, tail.location,
                publishedPlacementIntervals.map { interval ->
                    SweptRailSpan(id, interval.railId, interval.minArcLengthInAbMeters, interval.maxArcLengthInAbMeters)
                },
                publishedPlacementIntervals, publishedBodyTraversal
            )
            val prepared = prepareSplit(
                geometry, !isReversed, id, name, frameRevision.get() + 1L,
                speed, isDerailed, clearMotion = true
            )
            try {
                applyPreparedStructure(prepared)
                // Reuse normal builders for this low-frequency edit; no new epoch or signal pass.
                world.rebuildSweptOccupancyIndex(epoch)
                world.publishWorldFrame(epoch)
            } catch (failure: Throwable) {
                throw world.physicsCoordinator.stall("Train reversal publication failed: train=$id", failure)
            }
            MainRailEventBus.publish(TrainSwitchDirectionEvent(this))
        }
    }

    private fun reverseOnMainThread() {
        isReversed = !isReversed
        cars.forEach(CoreCar::reverse)
        head.location = headBogie.activeLocation.forward(headCar.headBogieToEndPointOffset)
            ?: throw CannotMoveForwardException(this)
        tail.location = tailBogie.activeLocation.forward(-tailCar.tailBogieToEndPointOffset)
            ?: throw CannotMoveForwardException(this)
        updateActiveMotion(0.0)
    }

    internal data class PreparedTrainGeometry(
        val cars: List<CoreCar>,
        val headLocation: RailLocationWithFacing,
        val tailLocation: RailLocationWithFacing,
        val sweptSpans: List<SweptRailSpan>,
        val placementIntervals: List<RailInterval>,
        val bodyTraversal: List<RailTraversalSegmentSnapshot>
    )

    internal data class PreparedTrainStructure(
        val geometry: PreparedTrainGeometry,
        val frame: TrainFrameSnapshot,
        val reversedBogieLocations: List<List<RailLocationWithFacing>>,
        val clearMotion: Boolean
    )

    /**
     * Split immediately before [carId] in stable consist order.
     *
     * The original train retains the stable prefix and the new train receives [carId] and the
     * stable suffix. Identity, endpoint geometry and all extension results are prepared before
     * either train is published.
     */
    @ApiStatus.Experimental
    @OptIn(ExtensionData::class)
    @Throws(CannotCreateTrainException::class)
    override fun splitBefore(carId: String, newId: String): Future<Train> =
        world.physicsCoordinator.submitBetweenFrames {
            world.physicsCoordinator.withStructuralMutation {
                check(world.getTrain(id) === this) { "Cannot split a retired train '$id'" }
                check(!movementHeld) { "Cannot split a train with unavailable configuration" }
                val matchingIndices = cars.indices.filter { cars[it].id == carId }
                if (matchingIndices.size != 1 || matchingIndices.single() == 0) {
                    throw CannotCreateTrainException()
                }
                val splitIndex = matchingIndices.single()
                world.validateNewTrainIdentity(newId, newId)

                val decision = TrainSplitEvent(this, carId, newId)
                val listenersSucceeded = try {
                    MainRailEventBus.publishAndReportFailures(decision)
                } finally {
                    decision.freeze()
                }
                check(listenersSucceeded) { "Split decision listener failed: train=$id car=$carId" }
                check(!decision.isRejected) { "Split rejected: ${decision.rejectionReason}" }

                val splitComposition = structuralComposition.splitAt(cars.map { it.id }, splitIndex, newId)
                val resultUnitIds = (splitComposition.retained.units + splitComposition.separated.units)
                    .map { it.unitId }.toSet()
                require(world.allTrains.none { existing ->
                    existing !== this && existing.composition.units.any { it.unitId in resultUnitIds }
                }) { "Split result unit identity belongs to another train" }
                val retainedCars = cars.take(splitIndex)
                val separatedCars = cars.drop(splitIndex)
                val sourcePath = placementSegments(tail.location, head.location)
                val retainedSplit = prepareSplit(
                    prepareSplitGeometry(retainedCars, sourcePath), decision.retainedReversed,
                    id, name, frameRevision.get() + 1L,
                    if (decision.holdResultMovement) 0.0 else speed, isDerailed,
                    clearMotion = decision.holdResultMovement || decision.retainedReversed != isReversed,
                    composition = splitComposition.retained
                )
                val separatedSourceGeometry = prepareSplitGeometry(separatedCars, sourcePath)
                val separatedSplit = prepareSplit(
                    separatedSourceGeometry, decision.separatedReversed,
                    newId, newId, 1L, 0.0, false, clearMotion = true,
                    composition = splitComposition.separated
                )
                val separatedExtensions = ExtensionDataContainer<TrainExtension>()
                val controllerUpdates = controller?.prepareConsistChange(retainedCars.toList(), null).orEmpty().toList()
                extensionDataContainer.extensionData.forEachIndexed { slot, extension ->
                    extension ?: return@forEachIndexed
                    val separatedExtension = extension.splitBefore(carId, newId)
                    require(separatedExtension !== extension) {
                        "split extension must be a dedicated instance: train=$id slot=$slot"
                    }
                    separatedExtensions.setExtensionDataAt(slot, separatedExtension)
                }

                val newTrain = CoreTrain(
                    id = newId,
                    cars = separatedCars,
                    world = world,
                    savedExtensionData = emptyMap(),
                    extensionDataContainer = separatedExtensions,
                    persistedHeadLocation = separatedSourceGeometry.headLocation,
                    persistedTailLocation = separatedSourceGeometry.tailLocation,
                    initialReversed = isReversed,
                    initialComposition = splitComposition.separated,
                    transferringCompositionFrom = this
                )
                var retainedHold: work.art1st.javarailways.core.api.physics.train.FormationMovementHold? = null
                var separatedHold: work.art1st.javarailways.core.api.physics.train.FormationMovementHold? = null
                try {
                    controllerUpdates.forEach { it.install() }
                    if (decision.holdResultMovement) controller?.commitStructuralStop()
                    newTrain.applyPreparedStructure(separatedSplit)
                    applyPreparedStructure(retainedSplit)
                    newTrain.compositionTransferPending = false
                    if (decision.holdResultMovement) {
                        retainedHold = installFormationMovementHold()
                        separatedHold = newTrain.installFormationMovementHold()
                    }
                    world.rebuildSweptOccupancyIndex(retainedSplit.frame.physicsEpoch)
                    world.publishWorldFrame(retainedSplit.frame.physicsEpoch)
                } catch (failure: Throwable) {
                    throw world.physicsCoordinator.stall("Train split publication failed: train=$id", failure)
                }
                MainRailEventBus.publish(TrainSplitSuccessEvent(
                    train = this,
                    separatedTrain = newTrain,
                    carId = carId,
                    originalCarIds = decision.originalCarIds,
                    originalReversed = decision.originalReversed,
                    retainedMovementHold = retainedHold,
                    separatedMovementHold = separatedHold
                ))
                newTrain
            }
        }

    private data class SplitEndpoints(
        val head: RailLocationWithFacing,
        val tail: RailLocationWithFacing
    )

    private fun splitEndpoints(
        splitCars: List<CoreCar>,
        path: List<RailSegment>
    ): SplitEndpoints {
        require(splitCars.isNotEmpty())
        val splitHeadCar = if (isReversed) splitCars.last() else splitCars.first()
        val splitTailCar = if (isReversed) splitCars.first() else splitCars.last()
        val splitHeadBogie = splitHeadCar.headBogie
        val splitTailBogie = splitTailCar.tailBogie
        val headOffset = splitHeadCar.headBogieToEndPointOffset
        val tailOffset = splitTailCar.tailBogieToEndPointOffset
        val splitHead = splitLocationOnPath(path, splitHeadBogie.location, headOffset)
        val splitTail = splitLocationOnPath(path, splitTailBogie.location, -tailOffset)
        return SplitEndpoints(splitHead, splitTail)
    }

    /** Resolve offsets on the occupied forward path, not a turnout's independent reverse choice. */
    private fun splitLocationOnPath(
        path: List<RailSegment>,
        location: RailLocationWithFacing,
        offset: Double
    ): RailLocationWithFacing {
        var precedingLength = 0.0
        var targetDistance: Double? = null
        for (segment in path) {
            if (segment.railView === location.railView &&
                location.arcLength >= segment.startArcLength - Curve.TOLERANCE &&
                location.arcLength <= segment.endArcLength + Curve.TOLERANCE
            ) {
                targetDistance = precedingLength + location.arcLength - segment.startArcLength + offset
                break
            }
            precedingLength += segment.totalArcLength
        }
        var remaining = targetDistance ?: throw CannotCreateTrainException()
        if (remaining < -Curve.TOLERANCE) throw CannotCreateTrainException()
        for (segment in path) {
            if (remaining <= segment.totalArcLength + Curve.TOLERANCE) {
                return segment.railView.getRailLocationAt(
                    segment.startArcLength + remaining.coerceIn(0.0, segment.totalArcLength)
                )
            }
            remaining -= segment.totalArcLength
        }
        throw CannotCreateTrainException()
    }

    private fun prepareSplitGeometry(
        splitCars: List<CoreCar>,
        sourcePath: List<RailSegment>
    ): PreparedTrainGeometry {
        val endpoints = splitEndpoints(splitCars, sourcePath)
        val segments = placementSegments(endpoints.tail, endpoints.head)
        return prepareStructureGeometry(splitCars, endpoints.head, endpoints.tail, segments)
    }

    /** Prepare publication geometry without changing cars, endpoints or ownership. */
    internal fun prepareStructureGeometry(
        resultCars: List<CoreCar>,
        headLocation: RailLocationWithFacing,
        tailLocation: RailLocationWithFacing,
        path: List<RailSegment>
    ): PreparedTrainGeometry {
        val sweptSpans = mergeSweptSpans(path.map(::spanFromSegment))
        return PreparedTrainGeometry(
            cars = resultCars,
            headLocation = headLocation,
            tailLocation = tailLocation,
            sweptSpans = sweptSpans,
            placementIntervals = intervalsFromSpans(sweptSpans),
            bodyTraversal = traversalSnapshots(path)
        )
    }

    private fun prepareSplit(
        originalGeometry: PreparedTrainGeometry,
        reversed: Boolean,
        resultId: String,
        resultName: String,
        revision: Long,
        resultSpeed: Double,
        resultDerailed: Boolean,
        clearMotion: Boolean,
        composition: TrainComposition = structuralComposition
    ): PreparedTrainStructure {
        val changeDirection = reversed != isReversed
        val geometry = if (changeDirection) originalGeometry.copy(
            headLocation = originalGeometry.tailLocation.reversed,
            tailLocation = originalGeometry.headLocation.reversed,
            bodyTraversal = originalGeometry.bodyTraversal.asReversed().map { segment ->
                RailTraversalSegmentSnapshot(
                    segment.railId,
                    if (segment.facing == ViewDirection.AB) ViewDirection.BA else ViewDirection.AB,
                    segment.endArcLengthInAbMeters,
                    segment.startArcLengthInAbMeters
                )
            }
        ) else originalGeometry
        val reversedBogieLocations = if (changeDirection) {
            geometry.cars.map { car -> car.bogies.map { it.location.reversed } }
        } else emptyList()
        val frame = prepareStructureFrame(
            geometry, reversed, resultId, resultName, revision, resultSpeed, resultDerailed, clearMotion,
            composition = composition
        )
        return PreparedTrainStructure(geometry, frame, reversedBogieLocations, clearMotion)
    }

    /** Capture a complete candidate frame; installation remains a separate owner-only operation. */
    internal fun prepareStructureFrame(
        geometry: PreparedTrainGeometry,
        reversed: Boolean,
        resultId: String = id,
        resultName: String = name,
        revision: Long = frameRevision.get() + 1L,
        resultSpeed: Double = speed,
        resultDerailed: Boolean = isDerailed,
        clearMotion: Boolean = false,
        preparedCars: Map<CoreCar, CarFrameSnapshot> = emptyMap(),
        composition: TrainComposition = structuralComposition
    ): TrainFrameSnapshot = captureFrame(
        revision = revision,
        physicsEpoch = publishedFrame.physicsEpoch,
        frameCars = geometry.cars,
        frameHead = geometry.headLocation,
        frameTail = geometry.tailLocation,
        framePlacementIntervals = geometry.placementIntervals,
        frameBodyTraversal = geometry.bodyTraversal,
        frameSweptSpans = geometry.sweptSpans,
        frameHeadTraversal = emptyList(),
        frameTailTraversal = emptyList(),
        frameTrainId = resultId,
        frameTrainName = resultName,
        frameSpeed = resultSpeed,
        frameDerailState = derailStateBuffer.get().let { state ->
            if (state.derailed == resultDerailed) state else DerailState(resultDerailed, null)
        },
        frameReversed = reversed,
        clearFrameMotion = clearMotion,
        preparedCars = preparedCars,
        frameComposition = composition
    )

    internal fun applyPreparedStructure(prepared: PreparedTrainStructure) {
        formationGeometryIdentity = Any()
        val geometry = prepared.geometry
        cars = geometry.cars
        structuralComposition = prepared.frame.composition
        if (prepared.reversedBogieLocations.isNotEmpty()) {
            cars.forEachIndexed { index, car ->
                car.applyPreparedOperatingReversal(prepared.reversedBogieLocations[index])
            }
        }
        isReversed = prepared.frame.reversed
        if (prepared.clearMotion) {
            cars.forEach { car ->
                car.clearMotionState()
                car.publishPhysicsState()
            }
        }
        head.location = geometry.headLocation
        tail.location = geometry.tailLocation
        head.publishPhysicsState()
        tail.publishPhysicsState()
        publishedSweptSpans = geometry.sweptSpans
        publishedPlacementIntervals = geometry.placementIntervals
        publishedBodyTraversal = geometry.bodyTraversal
        publishedHeadTraversal = emptyList()
        publishedTailTraversal = emptyList()
        pendingSweptSpans = null
        pendingHeadTraversal = null
        pendingTailTraversal = null
        frameRevision.set(prepared.frame.revision)
        publishedFrameRef.set(prepared.frame)
        world.publishTrainFrame(prepared.frame)
    }

    /** Collision-only entry; all coupling decisions and preparation belong to [TrainCoupling]. */
    internal fun linkFromCollisionOnOwner(
        other: CoreTrain,
        beforeCollision: TrainFrameSnapshot,
        otherBeforeCollision: TrainFrameSnapshot,
        topologyRevision: Long?,
        ownEnvelope: CollisionEnvelope?, otherEnvelope: CollisionEnvelope?
    ): Train = TrainCoupling.linkFromCollisionOnOwner(this, other, beforeCollision, otherBeforeCollision,
        topologyRevision, ownEnvelope, otherEnvelope)

    internal fun publishPhysicsState(refreshPlacement: Boolean = true) {
        cars.forEach { it.publishPhysicsState() }
        head.publishPhysicsState()
        tail.publishPhysicsState()
        val shouldRemoveController = synchronized(derailStateLock) {
            if (derailPhysicsDirty) {
                derailStateBuffer.commit()
                derailPhysicsDirty = false
            }
            removeControllerOnPhysicsPublish.also { removeControllerOnPhysicsPublish = false }
        }
        pendingSweptSpans?.let {
            publishedSweptSpans = it
            pendingSweptSpans = null
        }
        if (refreshPlacement) refreshPublishedPlacement()
        publishedHeadTraversal = pendingHeadTraversal ?: emptyList()
        publishedTailTraversal = pendingTailTraversal ?: emptyList()
        pendingHeadTraversal = null
        pendingTailTraversal = null
        if (shouldRemoveController) applyController(null)
    }

    /** Publishes one complete frame after this train's physics buffers have been flipped. */
    internal fun publishFrame(physicsEpoch: Long): TrainFrameSnapshot {
        require(physicsEpoch >= 0L) { "physicsEpoch must be non-negative" }
        val frame = captureFrame(
            revision = frameRevision.incrementAndGet(),
            physicsEpoch = physicsEpoch
        )
        while (true) {
            val current = publishedFrameRef.get()
            if (current != null && current.revision >= frame.revision) return current
            if (publishedFrameRef.compareAndSet(current, frame)) return frame
        }
    }

    /** Republishes committed geometry with changed metadata without waiting for another physics epoch. */
    internal fun publishMetadataFrame(): TrainFrameSnapshot = publishFrame(publishedFrame.physicsEpoch)

    private fun captureFrame(
        revision: Long,
        physicsEpoch: Long,
        frameCars: List<CoreCar> = cars,
        frameHead: RailLocationWithFacing = head.location,
        frameTail: RailLocationWithFacing = tail.location,
        framePlacementIntervals: List<RailInterval> = occupiedRailIntervals,
        frameBodyTraversal: List<RailTraversalSegmentSnapshot> = publishedBodyTraversal,
        frameSweptSpans: List<SweptRailSpan> = publishedSweptSpans,
        frameHeadTraversal: List<RailTraversalSegmentSnapshot> = publishedHeadTraversal,
        frameTailTraversal: List<RailTraversalSegmentSnapshot> = publishedTailTraversal,
        frameTrainId: String = id,
        frameTrainName: String = name,
        frameSpeed: Double = speed,
        frameDerailState: DerailState = derailStateBuffer.get(),
        frameReversed: Boolean = isReversed,
        clearFrameMotion: Boolean = false,
        preparedCars: Map<CoreCar, CarFrameSnapshot> = emptyMap(),
        frameComposition: TrainComposition = structuralComposition
    ): TrainFrameSnapshot = TrainFrameSnapshot.create(
        trainId = frameTrainId,
        trainName = frameTrainName,
        revision = revision,
        physicsEpoch = physicsEpoch,
        speedMetersPerSecond = frameSpeed,
        derailed = frameDerailState.derailed,
        derailReason = frameDerailState.reason,
        reversed = frameReversed,
        cars = frameCars.map { car ->
            preparedCars[car] ?: CarFrameSnapshot.create(
                carId = car.id,
                lengthMeters = car.length,
                state = captureFrameState(car, clearFrameMotion),
                bogies = car.bogies.map { captureFrameState(it, clearFrameMotion) }
            )
        },
        head = RailPositionSnapshot.capture(frameHead, frameHead.facing),
        tail = RailPositionSnapshot.capture(frameTail, frameTail.facing),
        occupiedIntervals = framePlacementIntervals,
        bodyTraversal = frameBodyTraversal,
        sweptSpans = frameSweptSpans.map { span ->
            SweptRailSpanSnapshot(span.railId, span.minArcAB, span.maxArcAB)
        },
        headTraversal = frameHeadTraversal,
        tailTraversal = frameTailTraversal,
        composition = frameComposition
    )

    private fun captureFrameState(
        renderable: Renderable,
        clearMotion: Boolean
    ): RenderableFrameSnapshot {
        val state = RenderableFrameSnapshot.capture(renderable)
        return if (clearMotion) state.copy(velocityX = 0.0, velocityY = 0.0, velocityZ = 0.0) else state
    }

    private fun currentRailLocations(): List<RailLocationWithFacing> = buildList {
        add(head.location)
        cars.forEach { car -> car.bogies.forEach { add(it.location) } }
        add(tail.location)
    }

    private fun activeRailLocations(): List<RailLocationWithFacing> = buildList {
        add(head.activeLocation)
        cars.forEach { car -> car.bogies.forEach { add(it.activeLocation) } }
        add(tail.activeLocation)
    }

    private fun spansFromLocations(locations: List<RailLocationWithFacing>): List<SweptRailSpan> {
        val spans = locations.groupBy { it.rail.id }.map { (railId, railLocations) ->
            val arcs = railLocations.map { it.arcLengthIn(ViewDirection.AB) }
            SweptRailSpan(id, railId, arcs.min(), arcs.max())
        }.toMutableList()
        // When the train straddles a rail boundary, sampled endpoints/bogies can leave an
        // artificial gap around the junction. Fill that connected boundary so physical locking
        // never observes the train as clear while its body still crosses the turnout anchor.
        locations.forEach { before ->
            locations.forEach { after ->
                if (before.railView.nextRails.none { it === after.railView }) return@forEach
                spans += spanFromViewRange(
                    before.railView,
                    before.arcLength,
                    before.railView.curve.totalArcLength
                )
                spans += spanFromViewRange(after.railView, 0.0, after.arcLength)
            }
        }
        return mergeSweptSpans(spans)
    }

    private fun intervalsFromSpans(spans: List<SweptRailSpan>): List<RailInterval> =
        java.util.List.copyOf(spans.map { span ->
            RailInterval(span.railId, span.minArcAB, span.maxArcAB)
        })

    private fun spanFromSegment(segment: RailSegment): SweptRailSpan = spanFromViewRange(
        segment.railView,
        segment.startArcLength,
        segment.endArcLength
    )

    private fun traversalSnapshots(segments: List<RailSegment>): List<RailTraversalSegmentSnapshot> =
        segments.asSequence()
            // Crossing evidence must not inherit geometry/occupancy tolerance: even a very short
            // positive movement can cross a signal-system boundary.
            .filter { it.endArcLength > it.startArcLength }
            .map { segment ->
                val view = segment.railView
                val curveLength = view.curve.totalArcLength
                val startAB = if (view.viewDirection == ViewDirection.AB) {
                    segment.startArcLength
                } else {
                    curveLength - segment.startArcLength
                }
                val endAB = if (view.viewDirection == ViewDirection.AB) {
                    segment.endArcLength
                } else {
                    curveLength - segment.endArcLength
                }
                RailTraversalSegmentSnapshot(
                    railId = view.rail.id,
                    facing = view.viewDirection,
                    startArcLengthInAbMeters = startAB,
                    endArcLengthInAbMeters = endAB
                )
            }
            .toList()

    private fun refreshPublishedPlacement() {
        val segments = placementSegments(tail.location, head.location)
        publishedBodyTraversal = traversalSnapshots(segments)
        publishedPlacementIntervals = intervalsFromSpans(
            mergeSweptSpans(segments.map(::spanFromSegment))
        )
    }

    private fun spanFromViewRange(
        view: RailView,
        startArcLength: Double,
        endArcLength: Double
    ): SweptRailSpan {
        val curveLength = view.curve.totalArcLength
        val startAB = if (view.viewDirection == ViewDirection.AB) {
            startArcLength
        } else {
            curveLength - startArcLength
        }
        val endAB = if (view.viewDirection == ViewDirection.AB) {
            endArcLength
        } else {
            curveLength - endArcLength
        }
        return SweptRailSpan(id, view.rail.id, minOf(startAB, endAB), maxOf(startAB, endAB))
    }

    private fun mergeSweptSpans(spans: List<SweptRailSpan>): List<SweptRailSpan> =
        spans.groupBy { it.railId }.map { (railId, railSpans) ->
            SweptRailSpan(
                trainId = id,
                railId = railId,
                minArcAB = railSpans.minOf { it.minArcAB },
                maxArcAB = railSpans.maxOf { it.maxArcAB }
            )
        }

    override fun commit() {
        if (stagedPlacementDirection != null) {
            commitMaintenancePlacement()
            return
        }
        // Explicit command/test paths advance immutable state without invoking platform extensions.
        publishPhysicsState()
        publishFrame(publishedFrame.physicsEpoch)
    }

    private val destruction = Destruction()

    override fun destroy(): CompletionStage<Void> = destruction.run {
        world.physicsCoordinator.executeBetweenFrames {
            check(world.getTrain(id) === this) { "Cannot destroy a retired train '$id'" }
            val steps = DestructionSteps()
            steps.action { removeController() }
            steps.await { head.destroy() }
            steps.await { tail.destroy() }
            cars.forEach { car -> steps.await { car.destroyOwnedResources() } }
            steps.action { world.unregisterTrain(this) }
            steps.action { world.publishOwnerChanges(emptyList()) }
            steps.finish()
        }.thenCompose { it }.whenComplete { _, failure ->
            if (failure != null) world.physicsCoordinator.stall("Train destruction failed: train=$id", failure)
        }
    }

    /** Coupling has transferred the cars; retirement must never destroy their new owner's resources. */
    internal fun retireAfterCoupling(): CompletionStage<Void> = destruction.run {
        world.physicsCoordinator.ensureBetweenFrames("CoreTrain.retireAfterCoupling")
        val steps = DestructionSteps()
        steps.action { removeController() }
        steps.await { head.destroy() }
        steps.await { tail.destroy() }
        steps.action { world.unregisterTrain(this) }
        steps.finish()
    }

    final override fun setController(value: TrainController?) {
        if (_controller === value) return
        world.physicsCoordinator.ensureBetweenFrames("CoreTrain.setController")
        check(world.getTrain(id) === this) { "Cannot control a retired train '$id'" }
        require(value == null || value.train === this) { "Controller belongs to another train" }
        applyController(value)
    }

    /** Install already prepared immutable controller parameters while physics is excluded. */
    fun applyControllerConfiguration(
        expectedController: TrainController,
        updates: List<PreparedControllerStateUpdate>
    ) {
        world.physicsCoordinator.ensureBetweenFrames("CoreTrain.applyControllerConfiguration")
        check(world.getTrain(id) === this && _controller === expectedController) {
            "Controller no longer owns train '$id'"
        }
        updates.forEach { it.install() }
    }

    /** Common exact registration install; physics-origin derail calls it only at publication. */
    private fun applyController(value: TrainController?) {
        world.physicsCoordinator.replaceController(this, _controller, value)
        _controller = value
    }

    fun removeController() {
        setController(null)
    }

    internal fun retirePublicationOwnership() {
        cars.forEach { it.retirePublicationOwnership(this) }
    }

    @MainThread
    override fun derail(reason: String) {
        ThreadGuard.ensureMainThread("CoreTrain.derail")
        require(reason.isNotBlank())
        world.physicsCoordinator.withStructuralMutation {
            check(world.getTrain(id) === this) { "Cannot derail a retired train '$id'" }
            val changed = synchronized(derailStateLock) {
                if (activeDerailState.derailed) return@synchronized false
                publishDerailState(DerailState(true, reason))
                true
            }
            if (!changed) return@withStructuralMutation
            try {
                removeController()
                updateActiveMotion(0.0)
                world.publishOwnerChanges(listOf(this))
            } catch (failure: Throwable) {
                throw world.physicsCoordinator.stall("Train derail publication failed: train=$id", failure)
            }
            MainRailEventBus.publish(DerailEvent(this, reason))
        }
    }

    /** Submit an external request; owner callers can use [derail] synchronously in their phase. */
    fun derailAsync(reason: String): CompletableFuture<Unit> {
        require(reason.isNotBlank())
        return world.physicsCoordinator.submitBetweenFrames { derail(reason) }
    }

    internal fun markDerailedFromPhysics(reason: String) {
        require(reason.isNotBlank())
        synchronized(derailStateLock) {
            if (activeDerailState.derailed) return
            derailStateBuffer.set(DerailState(true, reason))
            derailPhysicsDirty = true
            removeControllerOnPhysicsPublish = true
        }
    }

    internal fun queuePublishedDerailNotification() = synchronized(derailStateLock) {
        val state = derailStateBuffer.get()
        if (state.derailed && pendingDerailNotificationReason == null) {
            pendingDerailNotificationReason = requireNotNull(state.reason)
        }
    }

    internal fun pollDerailNotificationReason(): String? = synchronized(derailStateLock) {
        pendingDerailNotificationReason.also { pendingDerailNotificationReason = null }
    }

    protected fun clearDerailed() {
        publishDerailState(DerailState(false, null))
    }

    private fun publishDerailState(state: DerailState) = synchronized(derailStateLock) {
        derailStateBuffer.set(state)
        derailStateBuffer.commit()
        derailStateBuffer.set(state)
        derailPhysicsDirty = false
        removeControllerOnPhysicsPublish = false
    }

    companion object {
        const val COLLISION_SPEED = 2.78 // ~10km/h
        private const val TAIL_END_SEARCH_MARGIN_METERS = 1.0

        /** Owns new car/bogie construction and initialization through successful train publication. */
        fun <T> createOwned(world: CoreWorld, create: TrainCreation.() -> T): T {
            val creation = TrainCreation(world)
            return try {
                creation.create().also { creation.finish() }
            } catch (failure: Throwable) {
                creation.discard(failure)
                throw failure
            }
        }

        @JvmStatic
        fun create(
            id: String,
            cars: List<CoreCar>,
            world: CoreWorld,
            name: String = id,
            extensionData: Map<String, SerializableExtensionData> = emptyMap(),
            composition: TrainComposition? = null
        ): CoreTrain {
            require(cars.all { it.train == null }) { "New train creation cannot borrow owned cars" }
            return try {
                CoreTrain(id, cars, world, extensionData, name, initialComposition = composition)
            } catch (failure: Throwable) {
                val cleanup = DestructionSteps()
                cars.forEach { car -> cleanup.await { car.destroyOwnedResources() } }
                cleanup.finish().whenComplete { _, cleanupFailure ->
                    if (cleanupFailure != null) {
                        failure.addSuppressed(cleanupFailure)
                        world.physicsCoordinator.stall("Failed train creation cleanup: train=$id", cleanupFailure)
                    }
                }
                throw failure
            }
        }

        private fun validateTrainName(value: String): String {
            require(value.isNotBlank() && value == value.trim() && value.none(Char::isISOControl)) {
                "train name must be non-blank trimmed text without control characters"
            }
            return value
        }

    }

    override fun save(): SavedTrainV1 {
        val savedExtension = extensionDataContainer.extensionData.collectSavedExtensionData("train '$id'")
        return SavedTrainV1(
            id,
            name,
            cars.map { it.save(installedReversed = it.isReversed xor isReversed) },
            isReversed,
            savedExtension,
            head.location.save(),
            tail.location.save(),
            structuralComposition.units.map { SavedTrainUnitComposition(it.unitId, it.carIds) }
        )
    }



}
