package work.art1st.mineRails.core.physics.train

import work.art1st.mineRails.core.api.control.TrainController
import work.art1st.mineRails.core.api.event.DerailEvent
import work.art1st.mineRails.core.api.event.processor.EventManager
import work.art1st.mineRails.core.api.event.PassSignalEvent
import work.art1st.mineRails.core.api.event.TrainSwitchDirectionEvent
import work.art1st.mineRails.core.physics.CoreWorld
import work.art1st.mineRails.core.api.physics.exception.CannotCreateTrainException
import work.art1st.mineRails.core.api.physics.exception.CannotMoveForwardException
import work.art1st.mineRails.core.api.physics.exception.PhysicsException
import work.art1st.mineRails.core.api.physics.rail.RailLocation
import work.art1st.mineRails.core.api.physics.train.Train
import work.art1st.mineRails.core.api.physics.train.TrainExtension
import work.art1st.mineRails.core.api.util.annotation.MainThread
import work.art1st.mineRails.core.api.util.annotation.AsyncEventApi
import work.art1st.mineRails.core.api.util.plugin.Extendable
import work.art1st.mineRails.core.api.util.plugin.ExtensionData
import work.art1st.mineRails.core.api.util.plugin.ExtensionDataContainer
import work.art1st.mineRails.core.api.util.plugin.PluginRegistry
import work.art1st.mineRails.core.api.util.plugin.SerializableExtensionData
import work.art1st.mineRails.core.api.util.tick.Synchronizable
import work.art1st.mineRails.core.physics.rail.RailLocationWithFacing
import work.art1st.mineRails.core.physics.rail.RailSegment
import work.art1st.mineRails.core.physics.rail.SweptRailSpan
import work.art1st.mineRails.core.api.util.Destroyable
import work.art1st.configLib.persistence.PersistentBy
import work.art1st.mineRails.core.util.trait.TrainComponentTrait
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
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
    val extensionDataContainer: ExtensionDataContainer<TrainExtension> = ExtensionDataContainer(),
) : Train, TrainComponentTrait<Train, TrainExtension>, Destroyable, Synchronizable, Extendable<TrainExtension> by extensionDataContainer, PersistentBy<SavedTrainV1> {
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
                signals.forEach { EventManager.fireAsync(PassSignalEvent(this, it)) }
            }

            val previousLocations = currentRailLocations()
            val traversedSegments = head.location.peekCurve(deltaS).toList()
            headBogie.moveForward(deltaS)
            updateAfterHeadBogie()
            pendingSweptSpans = mergeSweptSpans(
                spansFromLocations(previousLocations + activeRailLocations()) +
                    traversedSegments.map(::spanFromSegment)
            )
        } catch (e: PhysicsException) {
            setDerailed()
            EventManager.fireAsync(DerailEvent(this, e.toString()))
        }
    }

    @Throws(PhysicsException::class)
    fun setLocation(headLocation: RailLocationWithFacing) {
        clearDerailed()
        // 正面面朝此location
        if (isReversed) reverse()
        val previousLocations = currentRailLocations()
        headBogie.updateLocation(headLocation.forward(-headCar.headBogieToEndPointOffset) ?: throw CannotMoveForwardException(this))
        updateAfterHeadBogie()
        pendingSweptSpans = spansFromLocations(previousLocations + activeRailLocations())
    }

    private fun updateAfterHeadBogie() {
        val orderedCars = if (isReversed) cars.asReversed() else cars
        var prevCar = orderedCars.first()
        prevCar.updateByHeadBogie(headBogie.activeLocation)
        for (car in orderedCars.drop(1)) {
            car.updateByPrevCar(prevCar)
            prevCar = car
        }
        head.location =
            headBogie.activeLocation.forward(headCar.headBogieToEndPointOffset) ?: throw CannotMoveForwardException(this)
        tail.location =
            tailBogie.activeLocation.forward(-tailCar.tailBogieToEndPointOffset) ?: throw CannotMoveForwardException(this)
    }

    override fun reverse() {
        reverseInternal()
        EventManager.fire(TrainSwitchDirectionEvent(this))
    }

    internal fun reverseFromPhysics() {
        reverseInternal()
        world.tickManager.scheduleMainThreadPostTickTask {
            EventManager.fire(TrainSwitchDirectionEvent(this))
        }
    }

    private fun reverseInternal() {
        isReversed = !isReversed
        cars.forEach { obj: CoreCar -> obj.reverse() }
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

    private fun spanFromSegment(segment: RailSegment): SweptRailSpan {
        val curveLength = segment.railView.curve.totalArcLength
        val startAB = if (segment.railView.viewDirection == ViewDirection.AB) {
            segment.startArcLength
        } else {
            curveLength - segment.startArcLength
        }
        val endAB = if (segment.railView.viewDirection == ViewDirection.AB) {
            segment.endArcLength
        } else {
            curveLength - segment.endArcLength
        }
        return SweptRailSpan(id, segment.railView.rail.id, minOf(startAB, endAB), maxOf(startAB, endAB))
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

    internal fun commitRenderState() {
        cars.forEach { it.commit() }
        head.commit()
        tail.commit()
    }

    override fun commit() {
        // Explicit command/test paths still publish and render synchronously.
        publishPhysicsState()
        commitRenderState()
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

    protected fun setDerailed() {
        _isDerailed.set(true)
        removeController()
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
            extensionData: Map<String, SerializableExtensionData> = emptyMap()
        ): CoreTrain = CoreTrain(id, cars, world, extensionData)

    }

    override fun save(): SavedTrainV1 {
        val savedExtension = HashMap<String, SerializableExtensionData>()
        extensionDataContainer.extensionData.forEach { ext ->
            ext?.save()?.let { savedExtension[it.first] = it.second }
        }
        return SavedTrainV1(id, cars.map { it.save() }, savedExtension)
    }



}
