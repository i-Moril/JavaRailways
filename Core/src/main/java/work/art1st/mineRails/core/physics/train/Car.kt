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
 *
 * if (isReversed)： 相信分支预测器
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
    override var asset: Asset,
) : SynchronizedObject, Renderable {

    data class MotionState(
        var tangentialVelocity: Vector3D,
        var angularVelocity: Vector3D,
    )

    private var _position: CommitBuffer<Vector3D> = CommitBuffer(Vector3D.ZERO)
    override var position: Vector3D
        get() = _position.get()
        protected set(value) = _position.set(value)

    private var _direction: CommitBuffer<Vector3D> = CommitBuffer(Vector3D(1.0, 0.0, 0.0))
    // 渲染的朝向，与速度方向无关
    override var direction: Vector3D
        get() = _direction.get()
        protected set(value) = _direction.set(value)

    private var _normal: CommitBuffer<Vector3D> = CommitBuffer(Vector3D(0.0, 1.0, 0.0))
    override var normal: Vector3D
        get() = _normal.get()
        protected set(value) = _normal.set(value)

    var _motionState: CommitBuffer<MotionState> = CommitBuffer(MotionState(Vector3D.ZERO, Vector3D.ZERO))
    var motionState: MotionState
        get() = _motionState.get()
        private set(value) = _motionState.set(value)
    override val velocity: Vector3D
        get() = motionState.tangentialVelocity

    var isReversed: Boolean = false
        protected set

    val bogieA
        get() = bogies.first()

    /* Should be positive */
    val bogieAToEndPointOffset
        get() = length / 2 + bogieAOffset

    /* Should be negative */
    val bogieAOffset
        get() = bogiesOffsets.first()

    val bogieB
        get() = bogies.last()

    /* Should be positive */
    val bogieBToEndPointOffset
        get() = length / 2 - bogieBOffset

    protected val bogieDistance
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

    val headConnectorLength
        get() = if (isReversed) connectorLengthAtB else connectorLengthAtA
    val tailConnectorLength
        get() = if (isReversed) connectorLengthAtA else connectorLengthAtB

    protected data class TailBogieIntermediateResult(
        val tailBogieNewLocation: RailLocationWithFacing,
        val carNewPosition: Vector3D,
        val carNewDirection: Vector3D,
    )

    /**
     * 根据第一个转向架计算第二个转向架
     * @param headBogieNewPosition 第一个转向架的新位置
     * @return
     */
    @Throws(BogieException::class)
    protected fun calculatePositionByHeadBogie(headBogieNewPosition: Vector3D): TailBogieIntermediateResult {
        // 向后查看2倍转向架间距的长度
        val curves = headBogie.peekCurve(2 * (bogieAOffset - bogieBOffset))
        // 转向架与车厢连接处
        val headBogieConnectorPosition = headBogie.position.add(headBogie.normal * floorHeight)
        // 尝试在前方的曲线段上二分查找
        for (x in curves) {
            val curve = x.second
            val tailBogieCurveIdx = BinarySearch.searchInteger(
                { i: Int ->
                    val tailBogieNewPoint = curve.getPointAt(i)!!
                    val tailBogieConnectorPosition = tailBogieNewPoint.position.add(tailBogieNewPoint.normal * floorHeight)
                    val distance = headBogieConnectorPosition.distance(tailBogieConnectorPosition)

                    DoubleCompare.compare(distance - bogieDistance, Curve.TOLERANCE)
                },
                0, curve.totalPoints
            )
            if (tailBogieCurveIdx != null) {
                val tailBogieNewPoint = RailLocationWithFacing(x.first, tailBogieCurveIdx)
                val tailBogieConnectorPosition = tailBogieNewPoint.position + tailBogieNewPoint.normal * floorHeight
                // 必然指向前方
                val tailToHeadDirection = (tailBogieConnectorPosition - headBogieConnectorPosition).normalize()
                // 正常情况下应该是负数
                val headBogieOffset = if (isReversed) -bogieBOffset else bogieAOffset
                val carPosition = headBogieConnectorPosition + tailToHeadDirection * headBogieOffset
                return TailBogieIntermediateResult(tailBogieNewPoint, carPosition, tailToHeadDirection)
            }
        }
        throw BogieException(tailBogie)
    }

    /**
     * 此时bogieA已经被更新，根据新值计算下一个bogie的position
     */
    @Throws(BogieException::class)
    fun updateByHeadBogie(headBogieNewPosition: Vector3D) {
        val (tailBogieLocation, carPosition, tailToHeadDirection) = calculatePositionByHeadBogie(headBogieNewPosition)
        tailBogie.location = tailBogieLocation
        this.position = carPosition
        this.direction = if (isReversed) -tailToHeadDirection else tailToHeadDirection
        this.normal = (bogieA.normal + bogieB.normal) * 0.5     // Approx
        updateMotionStateByHeadBogie()
    }

    @Throws(BogieException::class)
    fun updateByPrevCar(prevCar: Car) {
        val prevCarTailPosition = getTailPosition(prevCar.position, prevCar.direction, prevCar.length, prevCar.isReversed)

        // 向后查看2倍转向架间距的长度（前车后转向架与本车前转向架）
        val curves = prevCar.tailBogie.peekCurve(-2 * (prevCar.tailBogieToEndPointOffset + headBogieToEndPointOffset))
        for (x in curves) {
            val intermediateResult = BinarySearch.searchInteger(
                { i ->
                    val curve = x.second
                    val bogieAPoint = curve.getPointAt(i)!!

                    calculatePositionByHeadBogie(bogieAPoint.position)
                },
                { intermediateResult: TailBogieIntermediateResult ->
                    val (tailBogieLocation, carPosition, tailToHeadDirection) = intermediateResult
                    val headPosition = getHeadPosition(carPosition, tailToHeadDirection, length, isReversed)
                    val connectorVector = headPosition - (prevCarTailPosition)

                    // 如果接头处向量（车钩）与车身夹角为钝角，说明太靠后了
                    if (connectorVector.dot(tailToHeadDirection) < 0) -1 else
                        DoubleCompare.compare(connectorVector.length() - headConnectorLength - prevCar.tailConnectorLength, Curve.TOLERANCE)
                },
                0, x.second.totalPoints
            )
            if (intermediateResult != null) {
                val (tailBogieLocation, carPosition, tailToHeadDirection) = intermediateResult
                tailBogie.location = tailBogieLocation
                this.position = carPosition
                this.direction = if (isReversed) -tailToHeadDirection else tailToHeadDirection
                this.normal = (bogieA.normal + bogieB.normal) * 0.5     // Approx
                updateMotionStateByHead(prevCar.tailVelocity())     // this.headVelocity = prevCar.tailVelocity
            }
        }
    }

    // 设置完转向架位置、commit前调用，更新motionState
    private fun updateMotionStateByHeadBogie() {
        // tail到head方向单位矢量
        val d = if (isReversed) -_direction.getActive() else _direction.getActive()
        // headBogie原始速度
        val v0 = headBogie._velocity.getActive()
        // headBogie沿杆方向速度
        val vd0s = v0.dot(d)
        val tailBogieTangent = tailBogie._location.getActive().tangent
        // tailBogie方向与car方向夹角
        val cosTheta = tailBogieTangent.dot(d)
        // 更新tailBogie的速度矢量
        val v1 = tailBogieTangent * (vd0s / cosTheta)
        tailBogie.velocity = v1
        // v1 = v0 + \omega x d
        // 不考虑绕杆自转，有
        // \omega = d x (v1 - v0) / |d|^2
        val omega = d.crossProduct(v1 - v0) // |d|=1
        val centreToHead = _position.getActive() - headBogie._location.getActive().position
        val vCar = v0 + omega.crossProduct(centreToHead)
        motionState = MotionState(vCar, omega)
    }

    // 已知
    private fun updateMotionStateByHead(headVelocity: Vector3D) {
        // tail到head方向单位矢量
        val d = if (isReversed) -_direction.getActive() else _direction.getActive()
        // head沿杆方向速度
        val vd0s = headVelocity.dot(d)
        val headBogieTangent = headBogie._location.getActive().tangent
        // headBogie方向与car方向夹角
        val cosTheta = headBogieTangent.dot(d)
        // 更新tailBogie的速度矢量
        val v1 = headBogieTangent * (vd0s / cosTheta)
        headBogie.velocity = v1
        updateMotionStateByHeadBogie()
    }

    private fun tailVelocity() : Vector3D {
        // tail到head方向单位矢量
        val d = if (isReversed) -_direction.getActive() else _direction.getActive()
        val v = _motionState.getActive().tangentialVelocity
        val omega = _motionState.getActive().angularVelocity
        // v1 = v0 + \omega x d
        return v + omega.crossProduct(d * (length / 2))
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
        _motionState.commit()
    }

    companion object {
        protected fun getHeadPosition(position: Vector3D, tailToHeadDirection: Vector3D, carLength: Double, isReversed: Boolean): Vector3D {
            return position - tailToHeadDirection * (carLength / 2)
        }

        protected fun getTailPosition(position: Vector3D, tailToHeadDirection: Vector3D, carLength: Double, isReversed: Boolean): Vector3D {
            return position + tailToHeadDirection * (carLength / 2)
        }
    }

}

