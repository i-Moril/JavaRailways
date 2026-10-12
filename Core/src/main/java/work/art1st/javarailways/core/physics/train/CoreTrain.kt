package work.art1st.javarailways.core.physics.train

import org.jetbrains.annotations.ApiStatus
import org.joml.times
import org.joml.Vector3d
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
import work.art1st.javarailways.core.util.trait.TrainComponentTrait
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import work.art1st.javarailways.core.api.physics.rail.ViewDirection

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
) : Train, TrainComponentTrait<Train, TrainExtension>, Destroyable, Synchronizable, Extendable<TrainExtension> by extensionDataContainer, PersistentBy<SavedTrainV1> {
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
    val controller: TrainController?
        get() = _controller
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
    private val frameRevision = AtomicLong(0L)
    private val publishedFrameRef = AtomicReference<TrainFrameSnapshot?>()

    /** Latest complete value-only frame; consumers must not supplement it from live train state. */
    override val publishedFrame: TrainFrameSnapshot
        get() = publishedFrameRef.get() ?: error("initial train frame has not been published: $id")

    /** Opaque state used to roll back an administrator maintenance relocation. */
    class PlacementSnapshot internal constructor(
        internal val trainId: String,
        internal val headLocation: RailLocationWithFacing,
        internal val reversed: Boolean,
        internal val derailed: Boolean,
        internal val derailReason: String?,
        internal val publishedSweptSpans: List<SweptRailSpan>
    )
    override val isDerailed: Boolean
        get() = derailStateBuffer.get().derailed
    override val derailReason: String?
        get() = derailStateBuffer.get().reason

    private val reversedBuffer = CommitBuffer(initialReversed)
    private var reversedPhysicsDirty = false
    override var isReversed: Boolean
        get() = reversedBuffer.get()
        protected set(value) {
            reversedBuffer.set(value)
            reversedBuffer.commit()
            reversedBuffer.set(value)
            reversedPhysicsDirty = false
        }
    private val activeIsReversed: Boolean
        get() = if (reversedPhysicsDirty) reversedBuffer.getActive() else reversedBuffer.get()
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
        world.validateNewTrainIdentity(id, name)
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
            this.initialize(savedExtensionData)
            publishedSweptSpans = spansFromLocations(currentRailLocations())
            publishedPlacementIntervals = intervalsFromSpans(publishedSweptSpans)
            publishedBodyTraversal = try {
                traversalSnapshots(placementSegments(tail.location, head.location))
            } catch (_: IllegalStateException) {
                // Command-style construction may relocate an initially unaligned consist immediately.
                // Such an initial frame is not admissible; the first committed placement is strict.
                emptyList()
            }
            publishedFrameRef.set(captureFrame(revision = 0L, physicsEpoch = 0L))
            cars.forEach { it.transferPublicationOwnership(this) }
        } catch (failure: Throwable) {
            // Only this constructor's registrations are owned here; its caller still owns the cars.
            runCatching { removeController() }.exceptionOrNull()?.let(failure::addSuppressed)
            runCatching { createdHead?.destroy() }.exceptionOrNull()?.let(failure::addSuppressed)
            runCatching { createdTail?.destroy() }.exceptionOrNull()?.let(failure::addSuppressed)
            if (attachedToWorld) {
                runCatching { world.removeTrain(this) }.exceptionOrNull()?.let(failure::addSuppressed)
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
        get() = controller?.speed ?: 0.0

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
        if (activeDerailState.derailed) {
            updateActiveMotion(0.0)
            return
        }

        require(deltaS >= 0.0) { "deltaS must >= 0" }

        if (world.physicsCoordinator.detectSweptCollision(this)) {
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

        try {
            // PassSignal detection
            val signals = head.location.peekSignals(deltaS)
            if (signals.isNotEmpty()) {
                signals.forEach { PhysicsRailEventBus.publish(PassSignalEvent(this, it)) }
            }

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
            updateActiveMotion(0.0)
            markDerailedFromPhysics("Train movement failed: train=$id, deltaSMeters=$deltaS: $e")
        }
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
        val previousHeadLocation = head.location
        val previousLocations = currentRailLocations()
        val previousPendingSweptSpans = pendingSweptSpans
        val wasDerailed = isDerailed
        val previousDerailReason = derailReason
        val wasReversed = isReversed
        val directionChanged = isReversed
        try {
            clearDerailed()
            // 正面面朝此location；成功后才发布方向事件。
            if (directionChanged) reverseOnMainThread()
            applyHeadLocation(headLocation)
            pendingSweptSpans = spansFromLocations(previousLocations + activeRailLocations())
            if (directionChanged) MainRailEventBus.publish(TrainSwitchDirectionEvent(this))
        } catch (failure: Exception) {
            runCatching {
                if (isReversed != wasReversed) reverseOnMainThread()
                applyHeadLocation(previousHeadLocation)
                pendingSweptSpans = previousPendingSweptSpans
            }.exceptionOrNull()?.let(failure::addSuppressed)
            publishDerailState(DerailState(wasDerailed, previousDerailReason))
            throw failure
        }
    }

    /** Capture a stable maintenance rollback point after the current physics epoch is published. */
    @MainThread
    fun capturePlacementSnapshot(): PlacementSnapshot {
        check(pendingSweptSpans == null) {
            "cannot capture placement while train physics state is pending: $id"
        }
        return PlacementSnapshot(
            trainId = id,
            headLocation = head.location,
            reversed = isReversed,
            derailed = isDerailed,
            derailReason = derailReason,
            publishedSweptSpans = publishedSweptSpans.toList()
        )
    }

    /** Restore a previously captured maintenance placement and publish it atomically. */
    @MainThread
    fun restorePlacementSnapshot(snapshot: PlacementSnapshot) {
        require(snapshot.trainId == id) { "placement snapshot belongs to ${snapshot.trainId}, not $id" }
        val directionChanged = isReversed != snapshot.reversed
        if (directionChanged) reverseOnMainThread()
        applyHeadLocation(snapshot.headLocation)
        publishDerailState(DerailState(snapshot.derailed, snapshot.derailReason))
        pendingSweptSpans = snapshot.publishedSweptSpans
        commit()
        if (directionChanged) MainRailEventBus.publish(TrainSwitchDirectionEvent(this))
    }

    /** Publish a validated maintenance placement without retaining its teleport sweep. */
    @MainThread
    fun commitMaintenancePlacement() {
        // Maintenance placement has no elapsed-time basis. Never republish velocity left by the
        // last controller epoch as though the teleported geometry had moved at that speed.
        updateActiveMotion(0.0)
        pendingSweptSpans = activePlacementEnvelope()
        commit()
    }

    /** Exact rail intervals occupied by the currently active placement from tail to head. */
    @MainThread
    fun activePlacementEnvelope(): List<SweptRailSpan> {
        val spans = placementSegments(tail.activeLocation, head.activeLocation).map(::spanFromSegment)
        return mergeSweptSpans(spans)
    }

    /** Ordered train-local placement from [from] to [to], following the currently selected path. */
    private fun placementSegments(
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

    /** Remove constructor side effects when persisted placement validation fails. */
    internal fun discardFailedLoad() {
        removeController()
        head.destroy()
        tail.destroy()
        cars.forEach { it.destroy() }
        world.removeTrain(this)
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
        reverseOnMainThread()
        publishPhysicsState()
        publishMetadataFrame()
        MainRailEventBus.publish(TrainSwitchDirectionEvent(this))
    }

    internal fun reverseFromPhysics() {
        reversedBuffer.set(!activeIsReversed)
        reversedPhysicsDirty = true
        cars.forEach(CoreCar::reverseFromPhysics)
        head.location = activeHeadBogie.activeLocation.forward(
            activeHeadCar.activeHeadBogieToEndPointOffset
        ) ?: throw CannotMoveForwardException(this)
        tail.location = activeTailBogie.activeLocation.forward(
            -activeTailCar.activeTailBogieToEndPointOffset
        ) ?: throw CannotMoveForwardException(this)
        // Direction reversal is published as a movement-free epoch.
        updateActiveMotion(0.0)
        world.tickManager.scheduleMainThreadPostTickTask {
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

    private data class PreparedSplitGeometry(
        val cars: List<CoreCar>,
        val headLocation: RailLocationWithFacing,
        val tailLocation: RailLocationWithFacing,
        val sweptSpans: List<SweptRailSpan>,
        val placementIntervals: List<RailInterval>,
        val bodyTraversal: List<RailTraversalSegmentSnapshot>
    )

    private data class PreparedSplit(
        val geometry: PreparedSplitGeometry,
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
        world.tickManager.scheduleMainThreadAfterCommitTask {
            world.physicsCoordinator.withStructuralMutation {
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

                val retainedCars = cars.take(splitIndex)
                val separatedCars = cars.drop(splitIndex)
                val sourcePath = placementSegments(tail.location, head.location)
                val retainedSplit = prepareSplit(
                    prepareSplitGeometry(retainedCars, sourcePath), decision.retainedReversed,
                    id, name, frameRevision.get() + 1L, speed, isDerailed,
                    clearMotion = decision.retainedReversed != isReversed
                )
                val separatedSourceGeometry = prepareSplitGeometry(separatedCars, sourcePath)
                val separatedSplit = prepareSplit(
                    separatedSourceGeometry, decision.separatedReversed,
                    newId, newId, 1L, 0.0, false, clearMotion = true
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
                    initialReversed = isReversed
                )
                controllerUpdates.forEach { it.install() }
                newTrain.applyPreparedSplit(separatedSplit)
                applyPreparedSplit(retainedSplit)
                MainRailEventBus.publish(TrainSplitSuccessEvent(
                    train = this,
                    separatedTrain = newTrain,
                    carId = carId,
                    originalCarIds = decision.originalCarIds,
                    originalReversed = decision.originalReversed
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
    ): PreparedSplitGeometry {
        val endpoints = splitEndpoints(splitCars, sourcePath)
        val segments = placementSegments(endpoints.tail, endpoints.head)
        val bodyTraversal = traversalSnapshots(segments)
        val sweptSpans = mergeSweptSpans(segments.map(::spanFromSegment))
        val placementIntervals = intervalsFromSpans(sweptSpans)
        return PreparedSplitGeometry(
            cars = splitCars,
            headLocation = endpoints.head,
            tailLocation = endpoints.tail,
            sweptSpans = sweptSpans,
            placementIntervals = placementIntervals,
            bodyTraversal = bodyTraversal
        )
    }

    private fun prepareSplit(
        originalGeometry: PreparedSplitGeometry,
        reversed: Boolean,
        resultId: String,
        resultName: String,
        revision: Long,
        resultSpeed: Double,
        resultDerailed: Boolean,
        clearMotion: Boolean
    ): PreparedSplit {
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
        val frame = captureFrame(
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
            frameDerailed = resultDerailed,
            frameReversed = reversed,
            clearFrameMotion = clearMotion
        )
        return PreparedSplit(
            geometry = geometry,
            frame = frame,
            reversedBogieLocations = reversedBogieLocations,
            clearMotion = clearMotion
        )
    }

    private fun applyPreparedSplit(prepared: PreparedSplit) {
        val geometry = prepared.geometry
        cars = geometry.cars
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

    /**
     * 新车以当前车的车头为车头
     */
    @Throws(CannotCreateTrainException::class)
    @ApiStatus.Experimental
    fun linkWith(otherTrain: CoreTrain) {
        if (world != otherTrain.world) throw CannotCreateTrainException()
        world.tickManager.scheduleMainThreadAfterCommitTask {
            // 操作ExtensionData
            forEachExtensionData {
                it.linkWith(otherTrain)
            }
            cars = cars + otherTrain.cars
            var prevCar = cars.first()
            for (car in cars.slice(1..cars.lastIndex)) {
                car.updateByPrevCar(prevCar)
                prevCar = car
            }
            tail.location =
                tailBogie.location.forward(-tailCar.tailBogieToEndPointOffset) ?: throw CannotCreateTrainException()
            otherTrain.removeController()
            otherTrain.head.destroy()
            otherTrain.tail.destroy()
            otherTrain.cars.forEach { it.transferPublicationOwnership(this) }
            world.removeTrain(otherTrain)
        }
    }

    internal fun publishPhysicsState() {
        cars.forEach { it.publishPhysicsState() }
        head.publishPhysicsState()
        tail.publishPhysicsState()
        if (reversedPhysicsDirty) {
            reversedBuffer.commit()
            reversedPhysicsDirty = false
        }
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
        refreshPublishedPlacement()
        publishedHeadTraversal = pendingHeadTraversal ?: emptyList()
        publishedTailTraversal = pendingTailTraversal ?: emptyList()
        pendingHeadTraversal = null
        pendingTailTraversal = null
        if (shouldRemoveController) removeController()
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
        frameDerailed: Boolean = isDerailed,
        frameReversed: Boolean = isReversed,
        clearFrameMotion: Boolean = false
    ): TrainFrameSnapshot = TrainFrameSnapshot.create(
        trainId = frameTrainId,
        trainName = frameTrainName,
        revision = revision,
        physicsEpoch = physicsEpoch,
        speedMetersPerSecond = frameSpeed,
        derailed = frameDerailed,
        reversed = frameReversed,
        cars = frameCars.map { car ->
            CarFrameSnapshot.create(
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
        tailTraversal = frameTailTraversal
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
        // Explicit command/test paths advance immutable state without invoking platform extensions.
        publishPhysicsState()
        publishFrame(publishedFrame.physicsEpoch)
    }

    override fun destroy() {
        world.tickManager.scheduleMainThreadAfterCommitTask {
            removeController()
            head.destroy()
            tail.destroy()
            cars.forEach { obj: CoreCar -> obj.destroy() }
            world.removeTrain(this)
        }
    }

    open fun canLink(otherTrain: CoreTrain): Boolean = true

    fun setController(value: TrainController?) {
        _controller?.let { world.physicsCoordinator.unregister(it) }
        _controller = value
        value?.let { world.physicsCoordinator.register(it) }
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
        world.awaitCurrentPhysicsEpoch()
        val changed = synchronized(derailStateLock) {
            if (activeDerailState.derailed) return@synchronized false
            publishDerailState(DerailState(true, reason))
            true
        }
        if (!changed) return
        removeController()
        updateActiveMotion(0.0)
        publishPhysicsState()
        world.publishTrainFrame(publishMetadataFrame())
        MainRailEventBus.publish(DerailEvent(this, reason))
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

        @JvmStatic
        fun create(
            id: String,
            cars: List<CoreCar>,
            world: CoreWorld,
            name: String = id,
            extensionData: Map<String, SerializableExtensionData> = emptyMap()
        ): CoreTrain = CoreTrain(id, cars, world, extensionData, name)

        private fun validateTrainName(value: String): String {
            require(value.isNotBlank() && value == value.trim() && value.none(Char::isISOControl)) {
                "train name must be non-blank trimmed text without control characters"
            }
            return value
        }

    }

    override fun save(): SavedTrainV1 {
        val savedExtension = HashMap<String, SerializableExtensionData>()
        extensionDataContainer.extensionData.forEach { ext ->
            ext?.save()?.let { savedExtension[it.first] = it.second }
        }
        return SavedTrainV1(
            id,
            name,
            cars.map { it.save(installedReversed = it.isReversed xor isReversed) },
            isReversed,
            savedExtension,
            head.location.save(),
            tail.location.save()
        )
    }



}
