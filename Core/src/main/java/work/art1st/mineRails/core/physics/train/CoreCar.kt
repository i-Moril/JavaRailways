package work.art1st.mineRails.core.physics.train

import org.joml.*
import work.art1st.mineRails.core.physics.CoreWorld
import work.art1st.mineRails.core.api.physics.exception.BogieException
import work.art1st.mineRails.core.physics.rail.RailLocationWithFacing
import work.art1st.mineRails.core.util.CommitBuffer
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.api.physics.rail.Reversible
import work.art1st.mineRails.core.api.physics.train.Car
import work.art1st.mineRails.core.api.render.RenderableExtension
import work.art1st.mineRails.core.api.render.RenderableStateSnapshot
import work.art1st.mineRails.core.api.util.annotation.MainThread
import work.art1st.mineRails.core.util.trait.render.DynamicRenderableTrait
import work.art1st.mineRails.core.api.util.plugin.Extendable
import work.art1st.mineRails.core.api.util.plugin.ExtensionDataContainer
import work.art1st.mineRails.core.api.util.plugin.SerializableExtensionData
import work.art1st.mineRails.core.api.util.tick.Synchronizable
import work.art1st.configLib.persistence.PersistentBy

/**
 * 车厢类
 * 车厢视为刚体杆，由若干转向架支撑
 * |A ---- BogieA --- Car(Centre) --- BogieB --- B|
 * TODO: 计算中间的转向架
 *
 * if (isReversed)： 相信分支预测器
 */
class CoreCar @MainThread internal constructor(
    val id: String,
    /* 转向架 - 转向架偏移量 */
    override val bogies: List<CoreBogie>,
    val bogiesOffsets: List<Double>,
    val length: Double, // 车厢长度
    val floorHeight: Double,
    val connectorLengthAtA: Double,
    val connectorLengthAtB: Double,
    override val world: CoreWorld,
    savedExtensionData: Map<String, SerializableExtensionData>,
    val extensionDataContainer: ExtensionDataContainer<RenderableExtension<Car>> = ExtensionDataContainer()
) : Car, Synchronizable, Reversible, DynamicRenderableTrait<Car>, Extendable<RenderableExtension<Car>> by extensionDataContainer, PersistentBy<SavedCarV1> {
    data class MotionState(
        var tangentialVelocity: Vector3dc,
        var angularVelocity: Vector3dc,
    )

    private val _position: CommitBuffer<Vector3dc> = CommitBuffer(Vector3d(0.0, 0.0, 0.0))
    override var position: Vector3dc by _position
    private val activePositon
        get() = _position.getActive()

    private val _direction: CommitBuffer<Vector3dc> = CommitBuffer(Vector3d(1.0, 0.0, 0.0))
    // 渲染的朝向，与速度方向无关
    override var direction: Vector3dc by _direction
    private val activeDirection
        get() = _direction.getActive()

    private val _normal: CommitBuffer<Vector3dc> = CommitBuffer(Vector3d(0.0, 1.0, 0.0))
    override var normal: Vector3dc by _normal
    private val activeNormal
        get() = _normal.getActive()

    private var _motionState: CommitBuffer<MotionState> = CommitBuffer(MotionState(Vector3d(0.0, 0.0, 0.0), Vector3d(0.0, 0.0, 0.0)))
    var motionState: MotionState by _motionState
    override val velocity: Vector3dc
        get() = motionState.tangentialVelocity

    override var lastState: RenderableStateSnapshot = takeSnapshot()
    override var dirty: Boolean = false

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

    protected data class IntermediateResult(
        val headBogieNewLocation: RailLocationWithFacing,
        val tailBogieNewLocation: RailLocationWithFacing,
        val carNewPosition: Vector3dc,
        val carNewDirection: Vector3dc,
    )

    init {
        this.initialize(savedExtensionData)
    }

    /**
     * 根据第一个转向架计算第二个转向架
     * @param headBogieNewLocation 第一个转向架的新位置
     * @return
     */
    @Throws(BogieException::class)
    protected fun calculatePositionByHeadBogie(headBogieNewLocation: RailLocationWithFacing): IntermediateResult {
        dirty = true
        // 向后查看2倍转向架间距的长度
        val railSegments = headBogieNewLocation.peekCurve(2 * (bogieAOffset - bogieBOffset))
        // 转向架与车厢连接处的位置
        val headBogieConnectorPosition = headBogieNewLocation.position + headBogieNewLocation.normal * floorHeight
        // 尝试在后方的曲线段上二分查找
        // 注意到reversed TODO: bug
        val tailBogieNewLocation = railSegments.searchRailLocation(Curve.TOLERANCE) {
            tailBogieNewPoint ->
            val tailBogieConnectorPosition = tailBogieNewPoint.position + tailBogieNewPoint.normal * floorHeight
            val distance = headBogieConnectorPosition.distance(tailBogieConnectorPosition)

            distance - bogieDistance
        }?.reversed ?: throw BogieException(tailBogie)
        val tailBogieConnectorPosition = tailBogieNewLocation.position + tailBogieNewLocation.normal * floorHeight
        // 必然指向前方
        val tailToHeadDirection = (tailBogieConnectorPosition - headBogieConnectorPosition).normalize()
        // 正常情况下应该是负数
        val headBogieOffset = if (isReversed) -bogieBOffset else bogieAOffset
        val carPosition = headBogieConnectorPosition + tailToHeadDirection * headBogieOffset
        return IntermediateResult(headBogieNewLocation, tailBogieNewLocation, carPosition, tailToHeadDirection)
    }

    /**
     * 此时bogieA已经被更新，根据新值计算下一个bogie的position
     */
    @Throws(BogieException::class)
    fun updateByHeadBogie(headBogieNewLocation: RailLocationWithFacing) {
        val (headBogieLocation, tailBogieLocation, carPosition, tailToHeadDirection) = calculatePositionByHeadBogie(headBogieNewLocation)
        tailBogie.updateLocation(tailBogieLocation)
        this.position = carPosition
        this.direction = if (isReversed) -tailToHeadDirection else tailToHeadDirection
        this.normal = (bogieA.normal + bogieB.normal) * 0.5     // Approx
        updateMotionStateByHeadBogie()
    }

    @Throws(BogieException::class)
    fun updateByPrevCar(prevCar: CoreCar) {
        val prevCarTailPosition = getTailPosition(prevCar.position, prevCar.direction, prevCar.length)

        // 向后查看2倍转向架间距的长度（前车后转向架与本车前转向架）
        val intermediateResult = if (prevCar.tailBogieToEndPointOffset + headBogieToEndPointOffset > 0) {
            val railSegments = prevCar.tailBogie.activeLocation.peekCurve(-2 * (prevCar.tailBogieToEndPointOffset + headBogieToEndPointOffset))
            railSegments.searchRailLocation(Curve.TOLERANCE, {
                calculatePositionByHeadBogie(it.reversed)
            }, {
                val (headBogieLocation, tailBogieLocation, carPosition, tailToHeadDirection) = it
                val headPosition = getHeadPosition(carPosition, tailToHeadDirection, length)
                val connectorVector = headPosition - (prevCarTailPosition)

                // 如果接头处向量（车钩）与车身夹角为钝角，说明太靠后了
                if (connectorVector dot tailToHeadDirection < 0) Double.NEGATIVE_INFINITY else
                    connectorVector.length() - headConnectorLength - prevCar.tailConnectorLength
            }) ?: throw BogieException(tailBogie)
        } else {
            calculatePositionByHeadBogie(prevCar.tailBogie.activeLocation)
        }
        val (headBogieLocation, tailBogieLocation, carPosition, tailToHeadDirection) = intermediateResult
        headBogie.updateLocation(headBogieLocation)
        tailBogie.updateLocation(tailBogieLocation)
        this.position = carPosition
        this.direction = if (isReversed) -tailToHeadDirection else tailToHeadDirection
        val normal = bogieA.normal + bogieB.normal          // Approx
        this.normal = normal.projection(direction)
        updateMotionStateByHead(prevCar.tailVelocity())
    }

    // 设置完转向架位置、commit前调用，更新motionState
    private fun updateMotionStateByHeadBogie() {
        // tail到head方向单位矢量
        val d = if (isReversed) -activeDirection else activeDirection
        // headBogie原始速度
        val v0 = headBogie.activeVelocity
        // headBogie沿杆方向速度
        val vd0s = v0 dot d
        val tailBogieTangent = tailBogie.activeLocation.tangent
        // tailBogie方向与car方向夹角
        val cosTheta = tailBogieTangent dot d
        // 更新tailBogie的速度矢量`
        val v1 = tailBogieTangent * (vd0s / cosTheta)
        tailBogie.velocity = v1
        // v1 = v0 + \omega x d
        // 不考虑绕杆自转，有
        // \omega = d x (v1 - v0) / |d|^2
        val omega = d cross (v1 - v0) // |d|=1
        val centreToHead = _position.getActive() - headBogie.activeLocation.position
        val vCar = v0 + omega cross centreToHead
        motionState = MotionState(vCar, omega)
    }

    // 已知
    private fun updateMotionStateByHead(headVelocity: Vector3dc) {
        // tail到head方向单位矢量
        val d = if (isReversed) -_direction.getActive() else _direction.getActive()
        // head沿杆方向速度
        val vd0s = headVelocity dot d
        val headBogieTangent = headBogie.activeLocation.tangent
        // headBogie方向与car方向夹角
        val cosTheta = headBogieTangent dot d
        // 更新tailBogie的速度矢量
        val v1 = headBogieTangent * (vd0s / cosTheta)
        headBogie.velocity = v1
        updateMotionStateByHeadBogie()
    }

    private fun tailVelocity() : Vector3dc {
        // tail到head方向单位矢量
        val d = if (isReversed) -_direction.getActive() else _direction.getActive()
        val v = _motionState.getActive().tangentialVelocity
        val omega = _motionState.getActive().angularVelocity
        // v1 = v0 + \omega x d
        return v + omega cross (d * (length / 2))
    }

    @MainThread
    override fun reverse() {
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
        super.commit()
    }

    companion object {
        protected fun getHeadPosition(position: Vector3dc, tailToHeadDirection: Vector3dc, carLength: Double): Vector3dc {
            return position - tailToHeadDirection * (carLength / 2)
        }

        protected fun getTailPosition(position: Vector3dc, tailToHeadDirection: Vector3dc, carLength: Double): Vector3dc {
            return position + tailToHeadDirection * (carLength / 2)
        }
    }

    /**
     * 向量在平面上投影，输出为单位向量
     * @param normal: 要求为单位向量
     */
    private fun Vector3dc.projection(normal: Vector3dc): Vector3d {
        return (this - normal * (normal dot this)).normalize()
    }

    override fun save(): SavedCarV1 {
        val savedExtension = HashMap<String, SerializableExtensionData>()
        extensionDataContainer.extensionData.forEach { ext ->
            ext?.save()?.let { savedExtension[it.first] = it.second }
        }
        return SavedCarV1(
            id,
            bogieA.save(),
            bogieB.save(),
            CarConfigV1(
                length,
                floorHeight,
                null,
                bogieAOffset,
                null,
                bogieBOffset,
                connectorLengthAtA,
                connectorLengthAtB,
                savedExtension
            )
        )
    }
//
//    @Serializable
//    data class SavedCarV1(
//        val config: ConfigV1,
//        // val bogieALocation: RailLocationWithFacing,
//        // val bogieBLocation: RailLocationWithFacing,
//    )
}
