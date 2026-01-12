package work.art1st.mineRails.core.physics.train

import work.art1st.mineRails.core.asset.Asset
import work.art1st.mineRails.core.asset.Renderable
import work.art1st.mineRails.core.physics.World
import work.art1st.mineRails.core.physics.exception.BogieException
import work.art1st.mineRails.core.physics.rail.RailLocationWithFacing
import work.art1st.mineRails.core.physics.rail.curve.Curve
import work.art1st.mineRails.core.util.BinarySearch
import work.art1st.mineRails.core.util.CommitBuffer
import work.art1st.mineRails.core.util.DoubleCompare
import work.art1st.mineRails.core.util.Vector3D
import kotlin.Int
import kotlin.Throws

/**
 * 车厢类
 * 车厢视为刚体杆，由若干转向架支撑
 * |A ---- BogieA --- Car(Centre) --- BogieB --- B|
 * TODO: 计算中间的转向架
 */
open class Car internal constructor(
    val id: String,
    /* 转向架 - 转向架偏移量 */
    val bogies: List<Bogie>,
    val bogiesOffsets: List<Double>,
    val length: Double, // 车厢长度
    val floorHeight: Double,
    val connectorLengthAtA: Double,
    val connectorLengthAtB: Double,
    override val world: World,
    override var asset: Asset<Any>,
) : MovingObject, Renderable {
    private var _position: CommitBuffer<Vector3D> = CommitBuffer(Vector3D.ZERO)
    override var position: Vector3D
        get() = _position.get()
        protected set(value) = _position.set(value)

    private var _direction: CommitBuffer<Vector3D> = CommitBuffer(Vector3D(1.0, 0.0, 0.0))
    override var direction: Vector3D
        get() = _direction.get()
        protected set(value) = _direction.set(value)

    private var _normal: CommitBuffer<Vector3D> = CommitBuffer(Vector3D(0.0, 1.0, 0.0))
    override var normal: Vector3D
        get() = _normal.get()
        protected set(value) = _normal.set(value)

    var isReversed: Boolean = false
        protected set

    val bogieA
        get() = bogies.first()

    val bogieAToEndPointOffset
        get() = length / 2 + bogieAOffset

    /* Should be negative */
    val bogieAOffset
        get() = bogiesOffsets.first()

    val bogieB
        get() = bogies.last()

    val bogieBToEndPointOffset
        get() = length / 2 - bogieBOffset

    protected val bogieDistance: Double
        get() = bogieBOffset - bogieAOffset

    /* Should be positive */
    val bogieBOffset
        get() = bogiesOffsets.last()

    val headBogie
        get() = if (isReversed) bogieB else bogieA
    val tailBogie
        get() = if (isReversed) bogieA else bogieB
    /* Should be positive */
    val headBogieToEndPointOffset
        get() = if (isReversed) bogieBToEndPointOffset else bogieAToEndPointOffset
    /* Should be positive */
    val tailBogieToEndPointOffset
        get() = if (isReversed) bogieAToEndPointOffset else bogieBToEndPointOffset

    protected data class BogieBIntermediateResult(
        val bogieBNewLocation: RailLocationWithFacing,
        val carNewPosition: Vector3D,
        val carNewDirection: Vector3D,
    )

    /**
     * 根据第一个转向架位置计算第二个
     * @param bogieANewPosition 第一个转向架的新位置
     * @return
     */
    @Throws(BogieException::class)
    protected fun calculatePositionByBogieA(bogieANewPosition: Vector3D): BogieBIntermediateResult {
        // 向后查看2倍转向架间距的长度
        val curves = bogieA.peekCurve(2 * (bogieAOffset - bogieBOffset))
        // 转向架与车厢连接处
        val bogieAConnectorPosition = bogieA.position.add(bogieA.normal * floorHeight)
        for (x in curves) {
            val curve = x.second
            val bogieBCurveIdx = BinarySearch.searchInteger(
                { i: Int ->
                    val bogieBNewPoint = curve.getPointAt(i)!!
                    val bogieBConnectorPosition = bogieBNewPoint.position.add(bogieBNewPoint.normal * floorHeight)
                    val distance = bogieAConnectorPosition.distance(bogieBConnectorPosition)

                    DoubleCompare.compare(distance - bogieDistance, Curve.TOLERANCE)
                },
                0, curve.totalPoints
            )
            if (bogieBCurveIdx != null) {
                val bogieBNewPoint = RailLocationWithFacing(x.first, bogieBCurveIdx)
                val bogieBConnectorPosition = bogieBNewPoint.position.add(bogieBNewPoint.normal * floorHeight)
                val carDirection = (bogieBConnectorPosition - bogieAConnectorPosition).normalize()
                val carPosition = bogieAConnectorPosition - carDirection.multiply(bogieAOffset)
                return BogieBIntermediateResult(bogieBNewPoint, carPosition, carDirection)
            }
        }
        throw BogieException(bogieB)
    }

    /**
     * 此时bogieA已经被更新，根据新值计算下一个bogie的position
     */
    @Throws(BogieException::class)
    fun updateByBogieA(bogieANewPosition: Vector3D) {
        val (bogieBLocation, carPosition, carDirection) = calculatePositionByBogieA(bogieANewPosition)
        bogieB.location = bogieBLocation
        this.position = carPosition
        this.direction = carDirection
        this.normal = (bogieA.normal + bogieB.normal) * 0.5
    }

    @Throws(BogieException::class)
    fun updateByPrevCar(prevCar: Car) {
        val prevCarBPosition = getBEndPointPosition(prevCar.position, prevCar.direction, prevCar.length)

        // 向后查看2倍转向架到端头+车厢长度的弧长
        val curves = prevCar.bogieB.peekCurve(-2 * (length + prevCar.bogieBOffset))
        for (x in curves) {
            val intermediateResult = BinarySearch.searchInteger(
                { i ->
                    val curve = x.second
                    val bogieAPoint = curve.getPointAt(i)!!

                    calculatePositionByBogieA(bogieAPoint.position)
                },
                { intermediateResult: BogieBIntermediateResult ->
                    val (bogieBLocation, carPosition, carDirection) = intermediateResult
                    val aPosition = getAEndPointPosition(carPosition, carDirection, length)
                    val connectorVector = aPosition - (prevCarBPosition)

                    // 如果接头处向量（车钩）与车身夹角为钝角，说明太靠后了
                    if (connectorVector.dot(carDirection) < 0) -1 else
                        DoubleCompare.compare(connectorVector.length() - connectorLengthAtA - prevCar.connectorLengthAtB, Curve.TOLERANCE)
                },
                0, x.second.totalPoints
            )
            if (intermediateResult != null) {
                val (bogieBLocation, carPosition, carDirection) = intermediateResult
                bogieB.location = bogieBLocation
                this.position = carPosition
                this.direction = carDirection
                this.normal = (bogieA.normal + bogieB.normal) * 0.5
            }
        }
    }

    fun reverse() {
        isReversed = !isReversed
        bogies.forEach { it.reverse() }
    }

    override fun destroy() {
        bogies.forEach { it.destroy() }
        super.destroy()
    }

    override fun commit() {
        bogies.forEach { it.commit() }
        _position.commit()
        _direction.commit()
        _normal.commit()
    }

    companion object {
        protected fun getAEndPointPosition(position: Vector3D, direction: Vector3D, carLength: Double): Vector3D {
            return position - direction.normalize().multiply(carLength / 2)
        }

        protected fun getBEndPointPosition(position: Vector3D, direction: Vector3D, carLength: Double): Vector3D {
            return position + direction.normalize().multiply(carLength / 2)
        }
    }

}

