package work.art1st.mineRails.core.physics.train

import org.joml.*
import work.art1st.mineRails.core.physics.CoreWorld
import work.art1st.mineRails.core.api.physics.exception.BogieException
import work.art1st.mineRails.core.physics.rail.RailLocationWithFacing
import work.art1st.mineRails.core.physics.rail.RailSegments
import work.art1st.mineRails.core.util.CommitBuffer
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.api.physics.rail.Reversible
import work.art1st.mineRails.core.api.physics.train.Car
import work.art1st.mineRails.core.api.physics.train.Seat
import work.art1st.mineRails.core.api.render.RenderableExtension
import work.art1st.mineRails.core.api.render.RenderableStateSnapshot
import work.art1st.mineRails.core.api.util.annotation.MainThread
import work.art1st.mineRails.core.util.trait.render.DynamicRenderableTrait
import work.art1st.mineRails.core.api.util.plugin.Extendable
import work.art1st.mineRails.core.api.util.plugin.ExtensionDataContainer
import work.art1st.mineRails.core.api.util.plugin.SerializableExtensionData
import work.art1st.mineRails.core.api.util.tick.Synchronizable
import work.art1st.configLib.persistence.PersistentBy
import kotlin.math.absoluteValue
import kotlin.math.max
import java.util.concurrent.atomic.AtomicBoolean

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
    override val length: Double, // 车厢长度
    override val floorHeight: Double,
    val connectorLengthAtA: Double,
    val connectorLengthAtB: Double,
    override val world: CoreWorld,
    savedExtensionData: Map<String, SerializableExtensionData>,
    val extensionDataContainer: ExtensionDataContainer<RenderableExtension<Car>> = ExtensionDataContainer()
) : Car, Synchronizable, Reversible, DynamicRenderableTrait<Car>, Extendable<RenderableExtension<Car>> by extensionDataContainer, PersistentBy<SavedCarV1> {

    /**
     * 车厢内的座位列表，position/direction 由车厢当前状态实时计算。
     */
    override val seats: List<Seat> get() = _seats

    private val _seats = mutableListOf<Seat>()

    /**
     * 在车厢尾部添加一个座位。
     * 座位位置 = 车厢尾部偏移 + 面向方向。
     */
    fun addSeat(): Seat {
        val seatIndex = _seats.size
        // 座位在车厢中心偏尾部，面向前进方向
        val seatOffset = -length * 0.25
        val seat = object : Seat {
            override val car: Car get() = this@CoreCar
            override val seatIndex: Int get() = seatIndex
            override val position: Vector3dc
                get() = Vector3d(position).add(
                    direction.mul(seatOffset, Vector3d())
                ).add(
                    normal.mul(floorHeight + 0.5, Vector3d())
                )
            override val direction: Vector3dc get() = this@CoreCar.direction
        }
        _seats.add(seat)
        return seat
    }

    /**
     * 移除最后一个座位。
     */
    fun removeLastSeat(): Seat? = if (_seats.isNotEmpty()) _seats.removeAt(_seats.lastIndex) else null
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
    private var physicsDirty: Boolean = false
    private val renderPending = AtomicBoolean(false)

    var isReversed: Boolean = false
        protected set

    val bogieA
        get() = bogies.first()

    /* Should be positive. Distance from bogie A to the A/head endpoint. */
    val bogieAToEndPointOffset
        get() = length / 2 - bogieAOffset

    /* Should be negative */
    override val bogieAOffset
        get() = bogiesOffsets.first()

    val bogieB
        get() = bogies.last()

    /* Should be positive. Distance from bogie B to the B/tail endpoint. */
    val bogieBToEndPointOffset
        get() = length / 2 + bogieBOffset

    protected val bogieDistance
        get() = bogieAOffset - bogieBOffset

    /* Should be positive */
    override val bogieBOffset
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

    private fun connectorPosition(location: RailLocationWithFacing): Vector3dc =
        location.position + location.normal * floorHeight

    // 弧长 forward 的弦长误差 s³/(24R²)：bogieDistance=1.2m, R=5m → ~0.003m < TOLERANCE。
    // 外层 searchRailLocation 会消化此误差（见 updateByPrevCar），不会积累。
    @Throws(BogieException::class)
    private fun findTailBogieByHead(headBogieNewLocation: RailLocationWithFacing): RailLocationWithFacing {
        return headBogieNewLocation.forward(-bogieDistance) ?: throw BogieException(tailBogie)
    }

    @Throws(BogieException::class)
    private fun findHeadBogieByTail(tailBogieNewLocation: RailLocationWithFacing): RailLocationWithFacing {
        return tailBogieNewLocation.forward(bogieDistance) ?: throw BogieException(headBogie)
    }


    /**
     * 根据第一个转向架计算第二个转向架
     * @param headBogieNewLocation 第一个转向架的新位置
     * @return
     */
    @Throws(BogieException::class)
    protected fun calculatePositionByHeadBogie(headBogieNewLocation: RailLocationWithFacing): IntermediateResult {
        physicsDirty = true
        val headBogieConnectorPosition = connectorPosition(headBogieNewLocation)
        val tailBogieNewLocation = findTailBogieByHead(headBogieNewLocation)
        val tailBogieConnectorPosition = connectorPosition(tailBogieNewLocation)
        // 必然指向前方：tail -> head
        val tailToHeadDirection = (headBogieConnectorPosition - tailBogieConnectorPosition).normalize()
        // 正常情况下应该是负数
        val headBogieOffset = if (isReversed) -bogieBOffset else bogieAOffset
        // carPosition 基于转向架连接器位置（含 floorHeight）计算，
        // 代表车厢地板在曲线法线方向上的实际高度
        val carPosition = headBogieConnectorPosition - tailToHeadDirection * headBogieOffset
        return IntermediateResult(headBogieNewLocation, tailBogieNewLocation, carPosition, tailToHeadDirection)
    }

    /**
     * 根据后转向架计算前转向架。
     * 运行中当前车厢的后转向架通常已经有确定位置，因此这里从后往前解算更稳。
     */
    @Throws(BogieException::class)
    protected fun calculatePositionByTailBogie(tailBogieNewLocation: RailLocationWithFacing): IntermediateResult {
        physicsDirty = true
        val tailBogieConnectorPosition = connectorPosition(tailBogieNewLocation)
        val headBogieNewLocation = findHeadBogieByTail(tailBogieNewLocation)
        val headBogieConnectorPosition = connectorPosition(headBogieNewLocation)
        val tailToHeadDirection = (headBogieConnectorPosition - tailBogieConnectorPosition).normalize()
        val tailBogieOffset = if (isReversed) bogieAOffset else -bogieBOffset
        val carPosition = tailBogieConnectorPosition + tailToHeadDirection * tailBogieOffset
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
        val carDirection = if (isReversed) -tailToHeadDirection else tailToHeadDirection
        this.direction = carDirection
        this.normal = (headBogieLocation.normal + tailBogieLocation.normal).projection(carDirection)
        updateMotionStateByHeadBogie()
    }

    @Throws(BogieException::class)
    fun updateByPrevCar(prevCar: CoreCar) {
        val prevCarTailToHeadDirection = if (prevCar.isReversed) -prevCar.activeDirection else prevCar.activeDirection
        val prevCarTailPosition = getTailPosition(prevCar.activePositon, prevCarTailToHeadDirection, prevCar.length)
        val couplerLength = headConnectorLength + prevCar.tailConnectorLength
        val currentHeadEndToTailBogieDistance = length - tailBogieToEndPointOffset
        val tailBogieSearchDistance =
            prevCar.tailBogieToEndPointOffset + couplerLength + currentHeadEndToTailBogieDistance

        // 从前车尾转向架向后查找到本车尾转向架。
        val intermediateResult = if (tailBogieSearchDistance > 0) {
            val railSegments = prevCar.tailBogie.activeLocation.peekCurve(-tailBogieSearchDistance * 2)
            railSegments.searchRailLocation(Curve.TOLERANCE, {
                calculatePositionByTailBogie(it.reversed)
            }, {
                val (headBogieLocation, tailBogieLocation, carPosition, tailToHeadDirection) = it
                val headPosition = getHeadPosition(carPosition, tailToHeadDirection, length)
                val connectorVector = prevCarTailPosition - headPosition
                val connectorDistance = connectorVector dot tailToHeadDirection
                connectorDistance - couplerLength
            }) ?: throw BogieException(tailBogie)
        } else {
            calculatePositionByTailBogie(prevCar.tailBogie.activeLocation)
        }
        val (headBogieLocation, tailBogieLocation, carPosition, tailToHeadDirection) = intermediateResult
        headBogie.updateLocation(headBogieLocation)
        tailBogie.updateLocation(tailBogieLocation)
        this.position = carPosition
        val carDirection = if (isReversed) -tailToHeadDirection else tailToHeadDirection
        this.direction = carDirection
        this.normal = (headBogieLocation.normal + tailBogieLocation.normal).projection(carDirection)
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
        return v + omega cross (-d * (length / 2))
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

    /** Publishes Core state after all workers finish reading the previous epoch. */
    internal fun publishPhysicsState() {
        bogies.forEach { it.publishPhysicsState() }
        if (!physicsDirty) return
        _position.commit()
        _direction.commit()
        _normal.commit()
        _motionState.commit()
        physicsDirty = false
        renderPending.set(true)
    }

    /** Main-thread render notification; physics publication happens in the Coordinator. */
    override fun commit() {
        bogies.forEach { it.commit() }
        if (!renderPending.getAndSet(false)) return
        dirty = true
        super.commit()
    }

    companion object {
        @JvmStatic
        fun create(
            id: String,
            bogieA: CoreBogie,
            bogieB: CoreBogie,
            length: Double = 2.0,
            floorHeight: Double = 0.5,
            connectorLengthAtA: Double = 0.3,
            connectorLengthAtB: Double = 0.3,
            bogieAOffset: Double? = null,
            bogieBOffset: Double? = null,
            world: CoreWorld,
            extensionData: Map<String, SerializableExtensionData> = emptyMap()
        ): CoreCar {
            // 转向架间距默认为车厢长度的60%，A在前B在后
            // 若显式传入则使用传入值（来自 YAML 车定义）
            val bogieAOffsetVal = bogieAOffset ?: (length * 0.3)
            val bogieBOffsetVal = bogieBOffset ?: (-length * 0.3)
            return CoreCar(
                id = id,
                bogies = listOf(bogieA, bogieB),
                bogiesOffsets = listOf(bogieAOffsetVal, bogieBOffsetVal),
                length = length,
                floorHeight = floorHeight,
                connectorLengthAtA = connectorLengthAtA,
                connectorLengthAtB = connectorLengthAtB,
                world = world,
                savedExtensionData = extensionData
            )
        }

        protected fun getHeadPosition(position: Vector3dc, tailToHeadDirection: Vector3dc, carLength: Double): Vector3dc {
            return position + tailToHeadDirection * (carLength / 2)
        }

        protected fun getTailPosition(position: Vector3dc, tailToHeadDirection: Vector3dc, carLength: Double): Vector3dc {
            return position - tailToHeadDirection * (carLength / 2)
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
