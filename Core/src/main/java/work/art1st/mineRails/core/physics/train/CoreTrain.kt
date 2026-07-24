package work.art1st.mineRails.core.physics.train

import work.art1st.mineRails.core.api.control.TrainController
import work.art1st.mineRails.core.api.event.DerailEvent
import work.art1st.mineRails.core.api.event.processor.MainRailEventBus
import work.art1st.mineRails.core.api.event.processor.PhysicsRailEventBus
import work.art1st.mineRails.core.api.event.PassSignalEvent
import work.art1st.mineRails.core.api.event.TrainSwitchDirectionEvent
import work.art1st.mineRails.core.physics.CoreWorld
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.api.physics.exception.CannotCreateTrainException
import work.art1st.mineRails.core.api.physics.exception.CannotMoveForwardException
import work.art1st.mineRails.core.api.physics.exception.PhysicsException
import work.art1st.mineRails.core.api.physics.rail.RailLocation
import work.art1st.mineRails.core.api.physics.rail.RailPositionSnapshot
import work.art1st.mineRails.core.api.physics.train.CarFrameSnapshot
import work.art1st.mineRails.core.api.physics.train.SweptRailSpanSnapshot
import work.art1st.mineRails.core.api.physics.train.Train
import work.art1st.mineRails.core.api.physics.train.TrainExtension
import work.art1st.mineRails.core.api.physics.train.TrainFrameSnapshot
import work.art1st.mineRails.core.api.render.RenderableFrameSnapshot
import work.art1st.mineRails.core.api.util.annotation.MainThread
import work.art1st.mineRails.core.api.util.annotation.AsyncEventApi
import work.art1st.mineRails.core.api.util.plugin.Extendable
import work.art1st.mineRails.core.api.util.plugin.ExtensionData
import work.art1st.mineRails.core.api.util.plugin.ExtensionDataContainer
import work.art1st.mineRails.core.api.util.plugin.PluginRegistry
import work.art1st.mineRails.core.api.util.plugin.SerializableExtensionData
import work.art1st.mineRails.core.api.util.tick.Synchronizable
import work.art1st.mineRails.core.api.physics.rail.RailInterval
import work.art1st.mineRails.core.physics.rail.RailLocationWithFacing
import work.art1st.mineRails.core.physics.rail.RailSegment
import work.art1st.mineRails.core.physics.rail.RailView
import work.art1st.mineRails.core.physics.rail.SweptRailSpan
import work.art1st.mineRails.core.api.util.Destroyable
import work.art1st.configLib.persistence.PersistentBy
import work.art1st.mineRails.core.util.ThreadGuard
import work.art1st.mineRails.core.util.trait.TrainComponentTrait
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import work.art1st.mineRails.core.api.physics.rail.ViewDirection

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
    // 使用AtomicBoolean防止fire两遍Collision, 见下面fire
    private var _isDerailed: AtomicBoolean = AtomicBoolean(false)

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
        internal val publishedSweptSpans: List<SweptRailSpan>
    )
    override val isDerailed: Boolean
        get() = _isDerailed.get()
    @Volatile
    var isReversed: Boolean = false
        protected set
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
            headBogie.location.forward(headCar.headBogieToEndPointOffset) ?: throw CannotCreateTrainException(),
            this, TrainEnd.Type.HEAD
        )
        tail = TrainEnd(
            tailBogie.location.forward(-tailCar.tailBogieToEndPointOffset) ?: throw CannotCreateTrainException(),
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
        if (isDerailed) return

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
                    traversedSegments.map(::spanFromSegment)
            )
        } catch (e: PhysicsException) {
            derail(e.toString())
        }
    }

    @Throws(PhysicsException::class)
    fun setLocation(headLocation: RailLocationWithFacing) {
        val previousHeadLocation = head.location
        val previousLocations = currentRailLocations()
        val previousPendingSweptSpans = pendingSweptSpans
        val wasDerailed = isDerailed
        val wasReversed = isReversed
        val directionChanged = isReversed
        try {
            clearDerailed()
            // 正面面朝此location；成功后才发布方向事件。
            if (directionChanged) reverseInternal()
            applyHeadLocation(headLocation)
            pendingSweptSpans = spansFromLocations(previousLocations + activeRailLocations())
            if (directionChanged) MainRailEventBus.publish(TrainSwitchDirectionEvent(this))
        } catch (failure: Exception) {
            runCatching {
                if (isReversed != wasReversed) reverseInternal()
                applyHeadLocation(previousHeadLocation)
                pendingSweptSpans = previousPendingSweptSpans
            }.exceptionOrNull()?.let(failure::addSuppressed)
            _isDerailed.set(wasDerailed)
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
            publishedSweptSpans = publishedSweptSpans.toList()
        )
    }

    /** Restore a previously captured maintenance placement and publish it atomically. */
    @MainThread
    fun restorePlacementSnapshot(snapshot: PlacementSnapshot) {
        require(snapshot.trainId == id) { "placement snapshot belongs to ${snapshot.trainId}, not $id" }
        val directionChanged = isReversed != snapshot.reversed
        if (directionChanged) reverseInternal()
        applyHeadLocation(snapshot.headLocation)
        _isDerailed.set(snapshot.derailed)
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
        head.location = headBogie.location.forward(headCar.headBogieToEndPointOffset)
            ?: throw CannotCreateTrainException()
        tail.location = tailBogie.location.forward(-tailCar.tailBogieToEndPointOffset)
            ?: throw CannotCreateTrainException()
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
        val orderedCars = if (isReversed) cars.asReversed() else cars
        var prevCar = orderedCars.first()
        prevCar.updateByHeadBogie(headBogie.activeLocation, deltaS)
        for (car in orderedCars.drop(1)) {
            car.updateByPrevCar(prevCar, deltaS)
            prevCar = car
        }
        head.location =
            headBogie.activeLocation.forward(headCar.headBogieToEndPointOffset) ?: throw CannotMoveForwardException(this)
        tail.location =
            tailBogie.activeLocation.forward(-tailCar.tailBogieToEndPointOffset) ?: throw CannotMoveForwardException(this)
    }

    @MainThread
    override fun reverse() {
        ThreadGuard.ensureMainThread("CoreTrain.reverse")
        reverseInternal()
        MainRailEventBus.publish(TrainSwitchDirectionEvent(this))
    }

    internal fun reverseFromPhysics() {
        reverseInternal()
        world.tickManager.scheduleMainThreadPostTickTask {
            MainRailEventBus.publish(TrainSwitchDirectionEvent(this))
        }
    }

    private fun reverseInternal() {
        isReversed = !isReversed
        cars.forEach { obj: CoreCar -> obj.reverse() }
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
        pendingSweptSpans?.let {
            publishedSweptSpans = it
            pendingSweptSpans = null
        }
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
        val grouped = locations.groupBy { it.rail.id }
        return grouped.map { (railId, railLocations) ->
            val arcs = railLocations.map { it.arcLengthIn(ViewDirection.AB) }
            SweptRailSpan(id, railId, arcs.min(), arcs.max())
        }
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
        if (_isDerailed.compareAndSet(false, true)) {
            removeController()
            PhysicsRailEventBus.publish(DerailEvent(this, reason))
        }
    }

    internal fun markDerailedFromPhysics() {
        if (_isDerailed.compareAndSet(false, true)) {
            removeController()
        }
    }

    protected fun clearDerailed() {
        _isDerailed.set(false)
    }

    companion object {
        const val COLLISION_SPEED = 2.78 // ~10km/h

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
        return SavedTrainV1(id, name, cars.map { it.save() }, isReversed, savedExtension)
    }



}
