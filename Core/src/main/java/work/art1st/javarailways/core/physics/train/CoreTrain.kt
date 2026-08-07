package work.art1st.javarailways.core.physics.train

import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.api.event.DerailEvent
import work.art1st.javarailways.core.api.event.processor.MainRailEventBus
import work.art1st.javarailways.core.api.event.processor.PhysicsRailEventBus
import work.art1st.javarailways.core.api.event.PassSignalEvent
import work.art1st.javarailways.core.api.event.TrainSwitchDirectionEvent
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.exception.CannotCreateTrainException
import work.art1st.javarailways.core.api.physics.exception.CannotMoveForwardException
import work.art1st.javarailways.core.api.physics.exception.PhysicsException
import work.art1st.javarailways.core.api.physics.rail.RailLocation
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.train.CarFrameSnapshot
import work.art1st.javarailways.core.api.physics.train.SweptRailSpanSnapshot
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.physics.train.TrainExtension
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.api.render.RenderableFrameSnapshot
import work.art1st.javarailways.core.api.util.annotation.MainThread
import work.art1st.javarailways.core.api.util.annotation.AsyncEventApi
import work.art1st.javarailways.core.api.util.plugin.Extendable
import work.art1st.javarailways.core.api.util.plugin.ExtensionData
import work.art1st.javarailways.core.api.util.plugin.ExtensionDataContainer
import work.art1st.javarailways.core.api.util.plugin.PluginRegistry
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
import work.art1st.javarailways.core.util.ThreadGuard
import work.art1st.javarailways.core.util.trait.TrainComponentTrait
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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
) : Train, TrainComponentTrait<Train, TrainExtension>, Destroyable, Synchronizable, Extendable<TrainExtension> by extensionDataContainer, PersistentBy<SavedTrainV1> {
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
    private val activeDerailState: DerailState
        get() = if (derailPhysicsDirty) derailStateBuffer.getActive() else derailStateBuffer.get()

    @Volatile
    internal var publishedSweptSpans: List<SweptRailSpan> = emptyList()
        private set
    private var pendingSweptSpans: List<SweptRailSpan>? = null
    private val frameRevision = AtomicLong(0L)
    private val publishedFrameRef = AtomicReference<TrainFrameSnapshot?>()

    /** Latest complete value-only frame; consumers must not supplement it from live train state. */
    val publishedFrame: TrainFrameSnapshot
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

    private val reversedBuffer = CommitBuffer(false)
    private var reversedPhysicsDirty = false
    var isReversed: Boolean
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
        get() = publishedSweptSpans.map { span ->
            RailInterval(span.railId, span.minArcAB, span.maxArcAB)
        }

    init {
        head = TrainEnd(
            persistedHeadLocation
                ?: headBogie.location.forward(headCar.headBogieToEndPointOffset)
                ?: throw CannotCreateTrainException(),
            this, TrainEnd.Type.HEAD
        )
        tail = TrainEnd(
            persistedTailLocation
                ?: tailBogie.location.forward(-tailCar.tailBogieToEndPointOffset)
                ?: throw CannotCreateTrainException(),
            this, TrainEnd.Type.TAIL
        )
        world.addTrain(this)
        this.initialize(savedExtensionData)
        publishedSweptSpans = spansFromLocations(currentRailLocations())
        publishedFrameRef.set(captureFrame(revision = 0L, physicsEpoch = 0L))
    }

    val headCar
        get() = if (isReversed) cars.last() else cars.first()
    val tailCar
        get() = if (isReversed) cars.first() else cars.last()
    val headBogie: CoreBogie
        get() {
            return if (isReversed) cars.last().bogieB
            else cars.first().bogieA
        }
    val tailBogie: CoreBogie
        get() {
            return if (isReversed) cars.first().bogieA
            else cars.last().bogieB
        }
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
        if (activeDerailState.derailed) return

        require(deltaS >= 0.0) { "deltaS must >= 0" }

        if (world.physicsCoordinator.detectSweptCollision(this)) return

        if (deltaS == 0.0) {
            pendingSweptSpans = spansFromLocations(currentRailLocations())
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
            updateAfterHeadBogie(deltaS)
            pendingSweptSpans = mergeSweptSpans(
                spansFromLocations(previousLocations + activeRailLocations()) +
                    traversedSegments
                        .filter { kotlin.math.abs(it.endArcLength - it.startArcLength) > Curve.TOLERANCE }
                        .map(::spanFromSegment)
            )
        } catch (e: PhysicsException) {
            markDerailedFromPhysics(e.toString())
        }
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
        pendingSweptSpans = activePlacementEnvelope()
        commit()
    }

    /** Exact rail intervals occupied by the currently active placement from tail to head. */
    @MainThread
    fun activePlacementEnvelope(): List<SweptRailSpan> {
        val activeHead = head.activeLocation
        var view = tail.activeLocation.railView
        var startArc = tail.activeLocation.arcLength
        val spans = ArrayList<SweptRailSpan>()
        val visited = HashSet<Pair<java.util.UUID, ViewDirection>>()
        while (true) {
            val curveLength = view.curve.totalArcLength
            if (view === activeHead.railView && activeHead.arcLength + Curve.TOLERANCE >= startArc) {
                spans += spanFromViewRange(view, startArc, activeHead.arcLength)
                return mergeSweptSpans(spans)
            }
            val key = view.rail.id to view.viewDirection
            check(visited.add(key)) { "train placement loops before reaching its head: $id" }
            spans += spanFromViewRange(view, startArc, curveLength)
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
        updateAfterHeadBogie()
    }

    /** Rebuild derived car/end state without re-solving or moving exact persisted bogie locations. */
    internal fun restorePersistedPlacement(reversed: Boolean) {
        isReversed = reversed
        cars.forEach { it.restorePersistedState(reversed) }
        cars.forEach { car ->
            val actualBogieDistance = car.bogieA.location.position.distance(car.bogieB.location.position)
            val configuredBogieDistance = kotlin.math.abs(car.bogieAOffset - car.bogieBOffset)
            if (kotlin.math.abs(actualBogieDistance - configuredBogieDistance) >
                PERSISTED_BOGIE_POSITION_TOLERANCE_METERS
            ) throw CannotCreateTrainException()
        }
        head.location = requireNotNull(persistedHeadLocation) {
            "persisted train $id is missing its head location"
        }
        tail.location = requireNotNull(persistedTailLocation) {
            "persisted train $id is missing its tail location"
        }
        pendingSweptSpans = spansFromLocations(activeRailLocations())
    }

    /** Remove constructor side effects when persisted placement validation fails. */
    internal fun discardFailedLoad() {
        removeController()
        head.destroy()
        tail.destroy()
        cars.forEach { it.destroy() }
        world.removeTrain(this)
    }

    private fun updateAfterHeadBogie(deltaS: Double? = null) {
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
        tail.location = if (deltaS != null) {
            findTailEndForward(tail.activeLocation, activeTailBogie.activeLocation, deltaS)
        } else {
            activeTailBogie.activeLocation.forward(-activeTailCar.activeTailBogieToEndPointOffset)
                ?: throw CannotMoveForwardException(this)
        }
    }

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
    ): RailLocationWithFacing {
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
        return previousTail.peekCurve(endpointSearchDistance).searchRailLocation(Curve.TOLERANCE) { candidate ->
            val candidateDistance = forwardDistanceTo(previousTail, candidate, endpointSearchDistance)
                ?: throw CannotMoveForwardException("tail bisection left its train-local search path: train=$id")
            bogieDistance - candidateDistance - endOffset
        } ?: throw CannotMoveForwardException(
            "tail bisection did not converge: train=$id distance=$bogieDistance offset=$endOffset"
        )
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
    }

    /**
     * Starts from carNum 1
     */
    @OptIn(ExtensionData::class)
    @Throws(CannotCreateTrainException::class)
    fun splitFrom(carNum: Int, newId: String): Future<Train> =
        world.tickManager.scheduleMainThreadAfterCommitTask {
            if (carNum !in 1..length) throw CannotCreateTrainException()
            cars = cars.take(carNum - 1).toMutableList()
            tail.location = tailBogie.location.forward(-tailCar.tailBogieToEndPointOffset) ?: throw CannotCreateTrainException()

            val newTrainCars = cars.takeLast(length - carNum + 1) as MutableList<CoreCar>
            CoreTrain(newId, newTrainCars, world, emptyMap()).let { newTrain ->
                // 拷贝新的extensionData
                PluginRegistry.plugins.forEach {
                    newTrain.extensionDataContainer.setExtensionData(
                        it,
                        this.requireExtensionData<TrainExtension>(it).splitFrom(carNum, newId)
                    )
                }
                newTrain
            }
        }

    /**
     * 新车以当前车的车头为车头
     */
    @Throws(CannotCreateTrainException::class)
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

    private fun captureFrame(revision: Long, physicsEpoch: Long): TrainFrameSnapshot = TrainFrameSnapshot.create(
        trainId = id,
        trainName = name,
        revision = revision,
        physicsEpoch = physicsEpoch,
        speedMetersPerSecond = speed,
        derailed = isDerailed,
        reversed = isReversed,
        cars = cars.map { car ->
            CarFrameSnapshot.create(
                carId = car.id,
                lengthMeters = car.length,
                state = RenderableFrameSnapshot.capture(car),
                bogies = car.bogies.map(RenderableFrameSnapshot::capture)
            )
        },
        head = RailPositionSnapshot.capture(head.location, ViewDirection.AB),
        tail = RailPositionSnapshot.capture(tail.location, ViewDirection.AB),
        sweptSpans = publishedSweptSpans.map { span ->
            SweptRailSpanSnapshot(span.railId, span.minArcAB, span.maxArcAB)
        }
    )

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

    private fun spanFromSegment(segment: RailSegment): SweptRailSpan = spanFromViewRange(
        segment.railView,
        segment.startArcLength,
        segment.endArcLength
    )

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

    @OptIn(AsyncEventApi::class)
    override fun derail(reason: String) {
        require(reason.isNotBlank())
        val changed = synchronized(derailStateLock) {
            if (activeDerailState.derailed) return@synchronized false
            publishDerailState(DerailState(true, reason))
            true
        }
        if (!changed) return
        removeController()
        PhysicsRailEventBus.publish(DerailEvent(this, reason))
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
        private const val PERSISTED_BOGIE_POSITION_TOLERANCE_METERS = 1.0
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
            cars.map { it.save() },
            isReversed,
            savedExtension,
            head.location.save(),
            tail.location.save()
        )
    }



}
