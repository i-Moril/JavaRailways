package work.art1st.mineRails.core.physics.train

import work.art1st.mineRails.core.control.train.TrainController
import work.art1st.mineRails.core.api.event.CollisionEvent
import work.art1st.mineRails.core.api.event.DerailEvent
import work.art1st.mineRails.core.api.event.processor.EventManager
import work.art1st.mineRails.core.api.event.PassSignalEvent
import work.art1st.mineRails.core.physics.World
import work.art1st.mineRails.core.api.physics.exception.CannotCreateTrainException
import work.art1st.mineRails.core.api.physics.exception.CannotMoveForwardException
import work.art1st.mineRails.core.api.physics.exception.Collision
import work.art1st.mineRails.core.api.physics.exception.PhysicsException
import work.art1st.mineRails.core.api.util.SynchronizedObject
import work.art1st.mineRails.core.physics.rail.RailLocationWithFacing
import work.art1st.mineRails.core.util.Destroyable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 列车类
 * 由若干节车厢组成，车厢之间头尾相连
 */
open class Train private constructor(
    val id: String,
    cars: MutableList<Car>,
    val world: World
) : SynchronizedObject, Destroyable {
    var controller: TrainController? = null
    var cars: MutableList<Car> = cars
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

    }

    val headCar
        get() = if (isReversed) cars.last() else cars.first()
    val tailCar
        get() = if (isReversed) cars.first() else cars.last()
    val headBogie: Bogie
        get() {
            return if (isReversed) cars.last().bogieB
            else cars.first().bogieA
        }
    val tailBogie: Bogie
        get() {
            return if (isReversed) cars.first().bogieA
            else cars.first().bogieB
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
        headBogie.location = headLocation.forward(-headCar.headBogieToEndPointOffset) ?: throw CannotMoveForwardException(this)
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
        cars.forEach { obj: Car -> obj.reverse() }
    }

    /**
     * Starts from carNum 1
     */
    @Throws(CannotCreateTrainException::class)
    fun splitFrom(carNum: Int, newId: String): Train {
        if (carNum !in 1..length) throw CannotCreateTrainException()
        cars = cars.take(carNum - 1).toMutableList()
        tail.location = tailBogie.location.forward(-tailCar.tailBogieToEndPointOffset) ?: throw CannotCreateTrainException()

        val newTrainCars = cars.takeLast(length - carNum + 1) as MutableList<Car>
        val newTrain = Train(newId, newTrainCars, world)
        return newTrain
    }

    /**
     * 新车以当前车的车头为车头
     */
    @Throws(CannotCreateTrainException::class)
    fun linkWith(otherTrain: Train) {
        cars.addAll(otherTrain.cars)
        var prevCar = cars.first()
        for (car in cars.slice(1..cars.lastIndex)) {
            car.updateByPrevCar(prevCar)
            prevCar = car
        }
        tail.location = tailBogie.location.forward(-tailCar.tailBogieToEndPointOffset) ?: throw CannotCreateTrainException()
        world.removeTrain(otherTrain)
    }

    override fun commit() {
        cars.forEach { it.commit() }
        head.commit()
        tail.commit()
    }

    override fun destroy() {
        removeController()
        head.destroy()
        tail.destroy()
        cars.forEach { obj: Car -> obj.destroy() }
    }

    open fun canLink(otherTrain: Train): Boolean = true

    fun setController(value: TrainController?) {
        controller?.let { world.tickManager.unregister(it) }
        controller = value
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

        internal fun create(id: String, config: List<Pair<String, Car.ConfigV1>>, railLocation: RailLocationWithFacing, world: World): Train {
            // 先将railLocation初始化到一处
            val cars = config.map { it.second.buildCar(it.first, world, railLocation, railLocation) }
            val train = Train(
                id,
                cars.toMutableList(),
                world
            )
            train.setLocation(railLocation)
            return train
        }

        private fun fireCollisionEvent(e: Collision) {
            // 使用AtomicBoolean防止fire两遍Collision
            var trainA: Train
            var trainB: Train
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

}

