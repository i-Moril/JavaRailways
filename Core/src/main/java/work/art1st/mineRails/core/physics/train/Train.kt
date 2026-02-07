package work.art1st.mineRails.core.physics.train

import work.art1st.mineRails.core.control.train.TrainController
import work.art1st.mineRails.core.event.CollisionEvent
import work.art1st.mineRails.core.event.DerailEvent
import work.art1st.mineRails.core.event.EventManager
import work.art1st.mineRails.core.event.PassSignalEvent
import work.art1st.mineRails.core.physics.World
import work.art1st.mineRails.core.physics.exception.CannotCreateTrainException
import work.art1st.mineRails.core.physics.exception.CannotMoveForwardException
import work.art1st.mineRails.core.physics.exception.Collision
import work.art1st.mineRails.core.physics.exception.PhysicsException
import work.art1st.mineRails.core.physics.rail.RailLocationWithFacing
import work.art1st.mineRails.core.util.Destroyable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 列车类
 * 由若干节车厢组成，车厢之间头尾相连
 *
 * 假设TPS足够高，即使渲染一列车的不同部分属于不同的帧，之间的差异也不会特别大
 */
open class Train private constructor(
    val id: String,
    cars: MutableList<Car>,
    var controller: TrainController?,
    val world: World
) : MovingObject, Destroyable {
    var cars: MutableList<Car> = cars
        protected set
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
    @Throws(PhysicsException::class)
    fun moveTrainForward(deltaS: Double) {
        if (isDerailed) return

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

            // All operation finished
            commit()
        } catch (e: Collision) {
            fireCollisionEvent(e)
            throw e
        } catch (e: PhysicsException) {
            _isDerailed.set(true)
            EventManager.fire(DerailEvent(this, e.toString()))
            throw e
        }
    }

    @Throws(PhysicsException::class)
    fun setLocation(headLocation: RailLocationWithFacing) {
        // 正面面朝此location
        if (isReversed) reverse()
        headBogie.location = headLocation.forward(-headCar.headBogieToEndPointOffset) ?: throw CannotMoveForwardException(this)
        updateAfterHeadBogie()
    }

    private fun updateAfterHeadBogie() {
        var prevCar = cars.first()
        prevCar.updateByHeadBogie(headBogie.position)
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
        val newTrain = Train(newId, newTrainCars, null, world)
        world.addTrain(newTrain)
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
        world.trains.remove(otherTrain.id)
    }

    override fun commit() {
        cars.forEach { it.commit() }
        head.commit()
        tail.commit()
    }

    override fun destroy() {
        cars.forEach { obj: Car -> obj.destroy() }
    }

    open fun canLink(otherTrain: Train): Boolean = true

    companion object {
        const val COLLISION_SPEED = 2.78 // ~10km/h

//        fun create(): Train {
//           // TODO: Create a train
//        }

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

