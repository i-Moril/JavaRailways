package work.art1st.mineRails.core.physics.train

import work.art1st.mineRails.core.control.train.TrainController
import work.art1st.mineRails.core.api.event.CollisionEvent
import work.art1st.mineRails.core.api.event.DerailEvent
import work.art1st.mineRails.core.api.event.processor.EventManager
import work.art1st.mineRails.core.api.event.PassSignalEvent
import work.art1st.mineRails.core.physics.CoreWorld
import work.art1st.mineRails.core.api.physics.exception.CannotCreateTrainException
import work.art1st.mineRails.core.api.physics.exception.CannotMoveForwardException
import work.art1st.mineRails.core.api.physics.exception.Collision
import work.art1st.mineRails.core.api.physics.exception.PhysicsException
import work.art1st.mineRails.core.api.physics.train.Train
import work.art1st.mineRails.core.api.physics.train.TrainExtension
import work.art1st.mineRails.core.api.util.annotation.MainThread
import work.art1st.mineRails.core.api.util.plugin.Extendable
import work.art1st.mineRails.core.api.util.plugin.ExtensionData
import work.art1st.mineRails.core.api.util.plugin.ExtensionDataContainer
import work.art1st.mineRails.core.api.util.plugin.PluginRegistry
import work.art1st.mineRails.core.api.util.plugin.SerializableExtensionData
import work.art1st.mineRails.core.api.util.tick.Synchronizable
import work.art1st.mineRails.core.physics.rail.RailLocationWithFacing
import work.art1st.mineRails.core.util.Destroyable
import work.art1st.configLib.persistence.PersistentBy
import work.art1st.mineRails.core.util.trait.TrainComponentTrait
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

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
    private var _controller: TrainController? = null
    val controller: TrainController?
        get() = _controller
    override var cars: List<CoreCar> = cars
        protected set
    // 使用AtomicBoolean防止fire两遍Collision, 见下面fire
    private var _isDerailed: AtomicBoolean = AtomicBoolean(false)
    val isDerailed: Boolean
        get() = _isDerailed.get()
    var isReversed: Boolean = false
        protected set
    val length
        get() = cars.size
    var head: TrainEnd
        protected set
    val tail: TrainEnd

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
    val speed: Double
        get() = controller?.speed ?: 0.0

    /**
     * 将列车向前移动 delta S
     * 移动第一个转向架，然后更新整列车的位置和车厢状态
     * 
     * @param deltaS 移动的弧长距离
     * @throws IllegalArgumentException 如果输入参数无效
     * @throws IllegalStateException 如果列车没有转向架
     */
    fun moveTrainForward(deltaS: Double) {
        if (isDerailed) return

        require(deltaS >= 0.0) { "deltaS must >= 0" }

        if (deltaS == 0.0) {
            return
        }

        try {
            // Collision detection
            // 2 * deltaS: 保证相向而行的列车一定能被检查到
            // 假设两车相向而行，那么下一tick的移动距离为deltaS_1 + deltaS_2
            // 小于较大的deltaS * 2
            val obstacles = head.location.peekTrains(2 * deltaS)
            if (obstacles.isNotEmpty()) {
                // 不可能有超过一个trainEnd
                val previousEnd = obstacles[0]
                val prevTrain = previousEnd.train
                val prevSpeed = if (previousEnd.type == TrainEnd.Type.HEAD) -prevTrain.speed else prevTrain.speed
                if (speed - prevSpeed >= COLLISION_SPEED) {
                    throw Collision(this, prevTrain)
                } else {
                    if (prevTrain.canLink(this)) {
                        prevTrain.linkWith(this)
                    }
                }
            }

            // PassSignal detection
            val signals = head.location.peekSignals(deltaS)
            if (signals.isNotEmpty()) {
                signals.forEach { EventManager.fire(PassSignalEvent(this, it)) }
            }

            headBogie.moveForward(deltaS)
            updateAfterHeadBogie()
        } catch (e: PhysicsException) {
            if (e is Collision) {
                fireCollisionEvent(e)
            }
            setDerailed()
            EventManager.fire(DerailEvent(this, e.toString()))
        }
    }

    @Throws(PhysicsException::class)
    fun setLocation(headLocation: RailLocationWithFacing) {
        clearDerailed()
        // 正面面朝此location
        if (isReversed) reverse()
        headBogie.updateLocation(headLocation.forward(-headCar.headBogieToEndPointOffset) ?: throw CannotMoveForwardException(this))
        updateAfterHeadBogie()
    }

    private fun updateAfterHeadBogie() {
        var prevCar = cars.first()
        prevCar.updateByHeadBogie(headBogie.activeLocation)
        for (car in cars.slice(1..cars.lastIndex)) {
            car.updateByPrevCar(prevCar)
            prevCar = car
        }
        head.location =
            headBogie.location.forward(headCar.headBogieToEndPointOffset) ?: throw CannotMoveForwardException(this)
        tail.location =
            tailBogie.location.forward(-tailCar.tailBogieToEndPointOffset) ?: throw CannotMoveForwardException(this)
    }

    fun reverse() {
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
                        this.getExtensionData<TrainExtension>(it).splitFrom(carNum, newId)
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
            world.removeTrain(otherTrain)
        }
    }

    override fun commit() {
        cars.forEach { it.commit() }
        head.commit()
        tail.commit()
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
        _controller?.let { world.tickManager.unregister(it) }
        _controller = value
        value?.let { world.tickManager.register(it) }
    }

    fun removeController() {
        setController(null)
    }

    protected fun setDerailed() {
        _isDerailed.set(true)
        removeController()
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

        private fun fireCollisionEvent(e: Collision) {
            // 使用AtomicBoolean防止fire两遍Collision
            var trainA: CoreTrain
            var trainB: CoreTrain
            if (e.trainA.hashCode() < e.trainB.hashCode()) {
                trainA = e.trainA
                trainB = e.trainB
            } else {
                trainA = e.trainB
                trainB = e.trainA
            }
            if (trainA._isDerailed.compareAndSet(false, true)) {
                trainB._isDerailed.set(true)
                EventManager.fire(CollisionEvent(e.trainA, e.trainB))
            }
        }
    }

    override fun save(): SavedTrainV1 {
        val savedExtension = HashMap<String, SerializableExtensionData>()
        extensionDataContainer.extensionData.forEach { ext ->
            ext?.save()?.let { savedExtension[it.first] = it.second }
        }
        return SavedTrainV1(id, cars.map { it.save() }, savedExtension)
    }



}
