package work.art1st.javarailways.core.physics.train

import org.joml.*
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.api.physics.exception.BogieException
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.rail.RailSegments
import work.art1st.javarailways.core.util.CommitBuffer
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.Reversible
import work.art1st.javarailways.core.api.physics.train.Car
import work.art1st.javarailways.core.api.physics.train.Seat
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.render.RenderableExtension
import work.art1st.javarailways.core.api.render.RenderableStateSnapshot
import work.art1st.javarailways.core.api.util.annotation.MainThread
import work.art1st.javarailways.core.util.trait.render.DynamicRenderableTrait
import work.art1st.javarailways.core.api.util.plugin.Extendable
import work.art1st.javarailways.core.api.util.plugin.ExtensionDataContainer
import work.art1st.javarailways.core.api.util.plugin.SerializableExtensionData
import work.art1st.configLib.persistence.PersistentBy
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.absoluteValue

/**
 * 车厢类
 * 车厢视为刚体杆，当前由首尾两个转向架确定几何状态。
 * |A ---- BogieA --- Car(Centre) --- BogieB --- B|
 * TODO: 尚未实现中间转向架的几何定位。
 */
class CoreCar @MainThread internal constructor(
    override val id: String,
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
) : Car, Reversible, DynamicRenderableTrait<Car>, Extendable<RenderableExtension<Car>> by extensionDataContainer, PersistentBy<SavedCarV1> {

    private val trainOwner = AtomicReference<CoreTrain?>()

    override val train: Train? get() = trainOwner.get()

    /** Atomic implementation-only render ownership proof; process-local and never persisted. */
    fun isOwnedByPublicationStream(streamId: UUID): Boolean = trainOwner.get()?.publicationStreamId == streamId

    internal fun transferPublicationOwnership(owner: CoreTrain) {
        trainOwner.set(owner)
    }

    internal fun retirePublicationOwnership(owner: CoreTrain) {
        trainOwner.compareAndSet(owner, null)
    }

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
        private set
    private val activePosition
        get() = if (geometryPhysicsDirty) _position.getActive() else _position.get()

    private val _direction: CommitBuffer<Vector3dc> = CommitBuffer(Vector3d(1.0, 0.0, 0.0))
    // 渲染的朝向，与速度方向无关
    override var direction: Vector3dc by _direction
        private set
    override val operatingDirection: Vector3dc
        get() = if (isReversed) -direction else direction
    private val activeDirection
        get() = if (geometryPhysicsDirty) _direction.getActive() else _direction.get()

    private val _normal: CommitBuffer<Vector3dc> = CommitBuffer(Vector3d(0.0, 1.0, 0.0))
    override var normal: Vector3dc by _normal
        private set
    private val activeNormal
        get() = if (geometryPhysicsDirty) _normal.getActive() else _normal.get()

    private var _motionState: CommitBuffer<MotionState> = CommitBuffer(MotionState(Vector3d(0.0, 0.0, 0.0), Vector3d(0.0, 0.0, 0.0)))
    var motionState: MotionState by _motionState
        private set
    override val velocity: Vector3dc
        get() = motionState.tangentialVelocity

    override var lastState: RenderableStateSnapshot = takeSnapshot()
    private var geometryPhysicsDirty: Boolean = false
    private var motionPhysicsDirty: Boolean = false

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
    internal val activeIsReversed: Boolean
        get() = if (reversedPhysicsDirty) reversedBuffer.getActive() else reversedBuffer.get()

    val bogieA
        get() = bogies.first()

    /* Should be positive. Distance from bogie A to the A/head endpoint. */
    val bogieAToEndPointOffset
        get() = length / 2 - bogieAOffset

    /** 转向架 A 在稳定车体坐标中的有符号偏移，默认位于中心的 A 端一侧（正值）。 */
    override val bogieAOffset
        get() = bogiesOffsets.first()

    val bogieB
        get() = bogies.last()

    /* Should be positive. Distance from bogie B to the B/tail endpoint. */
    val bogieBToEndPointOffset
        get() = length / 2 + bogieBOffset

    protected val bogieDistance
        get() = bogieAOffset - bogieBOffset

    /** 转向架 B 在稳定车体坐标中的有符号偏移，默认位于中心的 B 端一侧（负值）。 */
    override val bogieBOffset
        get() = bogiesOffsets.last()

    val headBogie
        get() = if (isReversed) bogieB else bogieA
    val tailBogie
        get() = if (isReversed) bogieA else bogieB
    internal val activeHeadBogie
        get() = if (activeIsReversed) bogieB else bogieA
    internal val activeTailBogie
        get() = if (activeIsReversed) bogieA else bogieB
    /* Should be positive */
    val headBogieToEndPointOffset
        get() = if (isReversed) bogieBToEndPointOffset else bogieAToEndPointOffset
    /* Should be positive */
    val tailBogieToEndPointOffset
        get() = if (isReversed) bogieAToEndPointOffset else bogieBToEndPointOffset
    internal val activeHeadBogieToEndPointOffset
        get() = if (activeIsReversed) bogieBToEndPointOffset else bogieAToEndPointOffset
    internal val activeTailBogieToEndPointOffset
        get() = if (activeIsReversed) bogieAToEndPointOffset else bogieBToEndPointOffset

    val headConnectorLength
        get() = if (isReversed) connectorLengthAtB else connectorLengthAtA
    val tailConnectorLength
        get() = if (isReversed) connectorLengthAtA else connectorLengthAtB
    internal val activeHeadConnectorLength
        get() = if (activeIsReversed) connectorLengthAtB else connectorLengthAtA
    internal val activeTailConnectorLength
        get() = if (activeIsReversed) connectorLengthAtA else connectorLengthAtB

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

    /** One solve owns one forward path, including the inner bogie searches of each candidate. */
    private class SolvePath(val segments: RailSegments) {
        val length = segments.sumOf { it.totalArcLength }

        fun locationAt(distance: Double): RailLocationWithFacing {
            var remaining = distance.coerceIn(0.0, length)
            for ((index, segment) in segments.withIndex()) {
                if (remaining <= segment.totalArcLength || index == segments.lastIndex) {
                    return segment.railView.getRailLocationAt(
                        (segment.startArcLength + remaining).coerceIn(segment.startArcLength, segment.endArcLength)
                    )
                }
                remaining -= segment.totalArcLength
            }
            error("empty consist solve path")
        }

        fun distanceTo(target: RailLocationWithFacing): Double? {
            var traversed = 0.0
            for (segment in segments) {
                if (segment.railView === target.railView &&
                    target.arcLength >= segment.startArcLength - AXLE_TOLERANCE &&
                    target.arcLength <= segment.endArcLength + AXLE_TOLERANCE
                ) return traversed + target.arcLength - segment.startArcLength
                traversed += segment.totalArcLength
            }
            return null
        }
    }

    private data class SolveCandidate<T>(val distance: Double, val result: T, val error: Double)

    /**
     * Try the previous-tick prediction, bracket nearby, then safeguard secant steps with bisection.
     * A small step or an exhausted iteration budget is never accepted without a distance residual.
     */
    private fun <T> solveDistance(
        maximumDistance: Double,
        prediction: Double,
        tolerance: Double,
        sample: (Double) -> T,
        condition: (T) -> Double
    ): T? {
        if (!maximumDistance.isFinite() || maximumDistance < 0.0 || !prediction.isFinite()) return null
        fun evaluate(distance: Double): SolveCandidate<T> {
            val result = sample(distance)
            return SolveCandidate(distance, result, condition(result))
        }
        fun accepted(candidate: SolveCandidate<T>) =
            candidate.error.isFinite() && candidate.error.absoluteValue <= tolerance

        val predicted = evaluate(prediction.coerceIn(0.0, maximumDistance))
        if (accepted(predicted)) return predicted.result
        if (!predicted.error.isFinite()) return null
        var lo = predicted
        var hi = predicted
        var radius = maxOf(0.01, predicted.error.absoluteValue)
        while (true) {
            val lower = (predicted.distance - radius).coerceAtLeast(0.0)
            val upper = (predicted.distance + radius).coerceAtMost(maximumDistance)
            if (lower < lo.distance) lo = evaluate(lower)
            if (accepted(lo)) return lo.result
            if (upper > hi.distance) hi = evaluate(upper)
            if (accepted(hi)) return hi.result
            if (!lo.error.isFinite() || !hi.error.isFinite()) return null
            if ((lo.error < 0.0) != (hi.error < 0.0)) break
            if (lower == 0.0 && upper == maximumDistance) return null
            radius *= 2.0
        }
        repeat(60) {
            val width = hi.distance - lo.distance
            val secant = lo.distance - lo.error * width / (hi.error - lo.error)
            // Keeping each step away from the bracket ends guarantees contraction even when
            // a tangent changes quickly at a sampled curve or a rail joint.
            val nextDistance = if (secant.isFinite() &&
                secant > lo.distance + width * 0.1 && secant < hi.distance - width * 0.1
            ) secant else (lo.distance + hi.distance) * 0.5
            val next = evaluate(nextDistance)
            if (accepted(next)) return next.result
            if (!next.error.isFinite()) return null
            if ((lo.error < 0.0) != (next.error < 0.0)) hi = next else lo = next
        }
        return null
    }

    /** All placement, motion and restore centres use the same head-connector anchor. */
    private fun positionFromBogies(
        headLocation: RailLocationWithFacing,
        tailLocation: RailLocationWithFacing
    ): IntermediateResult {
        val headPosition = connectorPosition(headLocation)
        val chord = headPosition - connectorPosition(tailLocation)
        if (!chord.lengthSquared().isFinite() || chord.lengthSquared() <= 0.0) {
            throw BogieException(activeTailBogie)
        }
        val direction = chord.normalize()
        val headOffset = if (activeIsReversed) -bogieBOffset else bogieAOffset
        return IntermediateResult(headLocation, tailLocation, headPosition - direction * headOffset, direction)
    }

    @Throws(BogieException::class)
    protected fun calculatePositionByHeadBogie(
        headBogieNewLocation: RailLocationWithFacing,
        deltaS: Double? = null
    ): IntermediateResult {
        val headPosition = connectorPosition(headBogieNewLocation)
        if (deltaS != null) {
            require(deltaS >= 0.0 && deltaS.isFinite())
            val previousTail = activeTailBogie.activeLocation
            fun search(lower: Double): RailLocationWithFacing? {
                // A negative correction stays on this exact rail; never follow reverse turnout selection.
                val start = previousTail.railView.getRailLocationAt(previousTail.arcLength + lower)
                val path = SolvePath(start.peekCurve(deltaS - lower + FORWARD_SEARCH_RADIUS_METERS + bogieDistance * 2))
                val headDistance = path.distanceTo(headBogieNewLocation) ?: return null
                return solveDistance(headDistance, deltaS - lower, AXLE_TOLERANCE,
                    { distance -> path.locationAt(distance) },
                    { tail -> connectorPosition(tail).distance(headPosition) - bogieDistance }
                )
            }
            val lower = maxOf(deltaS - FORWARD_SEARCH_RADIUS_METERS, -previousTail.arcLength)
            val result = search(0.0) ?: lower.takeIf { it < 0.0 }?.let(::search)
                ?: throw BogieException(activeTailBogie)
            // Preserve the controller's exact new head, rather than reconstructing it from the tail.
            return positionFromBogies(headBogieNewLocation, result)
        }
        val path = SolvePath(headBogieNewLocation.peekCurve(-bogieDistance * 2 - FORWARD_SEARCH_RADIUS_METERS))
        val tail = solveDistance(path.length, bogieDistance, AXLE_TOLERANCE,
            { distance -> path.locationAt(distance).reversed },
            { location -> connectorPosition(location).distance(headPosition) - bogieDistance }
        ) ?: throw BogieException(activeTailBogie)
        return positionFromBogies(headBogieNewLocation, tail)
    }

    @Throws(BogieException::class)
    protected fun calculatePositionByTailBogie(tailBogieNewLocation: RailLocationWithFacing): IntermediateResult {
        val path = SolvePath(tailBogieNewLocation.peekCurve(bogieDistance * 2 + FORWARD_SEARCH_RADIUS_METERS))
        return calculatePositionByTailBogie(tailBogieNewLocation, path, 0.0, bogieDistance)
    }

    private fun calculatePositionByTailBogie(
        tailLocation: RailLocationWithFacing,
        path: SolvePath,
        tailDistance: Double,
        predictedSpacing: Double
    ): IntermediateResult {
        val tailPosition = connectorPosition(tailLocation)
        val head = solveDistance(
            minOf(path.length - tailDistance, bogieDistance * 2 + FORWARD_SEARCH_RADIUS_METERS),
            predictedSpacing, AXLE_TOLERANCE,
            { distance -> path.locationAt(tailDistance + distance) },
            { location -> connectorPosition(location).distance(tailPosition) - bogieDistance }
        ) ?: throw BogieException(activeHeadBogie)
        return positionFromBogies(head, tailLocation)
    }

    private fun searchForwardFromPreviousTail(
        deltaS: Double,
        condition: (IntermediateResult) -> Double
    ): IntermediateResult {
        require(deltaS >= 0.0 && deltaS.isFinite())
        val previousTail = activeTailBogie.activeLocation
        fun search(lower: Double): IntermediateResult? {
            val start = previousTail.railView.getRailLocationAt(previousTail.arcLength + lower)
            val requestedUpper = deltaS - lower + FORWARD_SEARCH_RADIUS_METERS
            val path = SolvePath(start.peekCurve(requestedUpper + bogieDistance * 2 + FORWARD_SEARCH_RADIUS_METERS))
            val previousTailDistance = path.distanceTo(previousTail) ?: return null
            val previousSpacing = path.distanceTo(activeHeadBogie.activeLocation)
                ?.minus(previousTailDistance) ?: bogieDistance
            var maximumDistance = requestedUpper
            if (path.length < requestedUpper + bogieDistance * 2) {
                // At a track end nominal axle arc length is insufficient: find the last feasible
                // tail using the same chord constraint before sampling any nested car candidates.
                val endPosition = connectorPosition(path.locationAt(path.length))
                val lastTail = solveDistance(path.length, path.length - previousSpacing, AXLE_TOLERANCE,
                    { distance -> distance },
                    { distance -> connectorPosition(path.locationAt(distance)).distance(endPosition) - bogieDistance }
                ) ?: return null
                maximumDistance = minOf(maximumDistance, lastTail)
            }
            return solveDistance(maximumDistance, deltaS - lower, COUPLER_TOLERANCE,
                { distance -> calculatePositionByTailBogie(path.locationAt(distance), path, distance, previousSpacing) },
                condition
            )
        }
        val lower = maxOf(deltaS - FORWARD_SEARCH_RADIUS_METERS, -previousTail.arcLength)
        return search(0.0) ?: lower.takeIf { it < 0.0 }?.let(::search)
            ?: throw BogieException(activeTailBogie)
    }


    /**
     * 根据已更新的运营头转向架定位运营尾转向架及车体；运营头可能是 A 或 B。
     */
    @Throws(BogieException::class)
    fun updateByHeadBogie(
        headBogieNewLocation: RailLocationWithFacing,
        deltaS: Double? = null
    ) {
        val (headBogieLocation, tailBogieLocation, carPosition, tailToHeadDirection) =
            calculatePositionByHeadBogie(headBogieNewLocation, deltaS)
        geometryPhysicsDirty = true
        activeTailBogie.updateLocation(tailBogieLocation)
        this.position = carPosition
        val carDirection = if (activeIsReversed) -tailToHeadDirection else tailToHeadDirection
        this.direction = carDirection
        this.normal = (headBogieLocation.normal + tailBogieLocation.normal).projection(carDirection)
        updateMotionStateByHeadBogie()
    }

    @Throws(BogieException::class)
    fun updateByPrevCar(prevCar: CoreCar, deltaS: Double? = null) {
        val prevCarTailToHeadDirection = if (prevCar.activeIsReversed) {
            -prevCar.activeDirection
        } else {
            prevCar.activeDirection
        }
        val prevCarTailPosition = getTailPosition(prevCar.activePosition, prevCarTailToHeadDirection, prevCar.length)
        val couplerLength = activeHeadConnectorLength + prevCar.activeTailConnectorLength
        val currentHeadEndToTailBogieDistance = length - activeTailBogieToEndPointOffset
        val tailBogieSearchDistance =
            prevCar.activeTailBogieToEndPointOffset + couplerLength + currentHeadEndToTailBogieDistance

        // Runtime searches forward from this car's published tail; explicit placement retains the
        // backward fallback because it has no previous-tick movement interval.
        val couplerError: (IntermediateResult) -> Double = { candidate ->
            val headPosition = getHeadPosition(
                candidate.carNewPosition,
                candidate.carNewDirection,
                length
            )
            val connectorVector = prevCarTailPosition - headPosition
            // Coupler length is a 3D distance. The longitudinal projection only supplies the
            // bisection sign after a candidate crosses to the wrong side of the leading car.
            val connectorDistance = connectorVector.length()
            val isOnExpectedSide = connectorVector dot candidate.carNewDirection >= 0.0
            if (isOnExpectedSide) {
                connectorDistance - couplerLength
            } else {
                -connectorDistance - couplerLength
            }
        }
        val intermediateResult = when {
            tailBogieSearchDistance <= 0 ->
                calculatePositionByTailBogie(prevCar.activeTailBogie.activeLocation)
            deltaS != null -> searchForwardFromPreviousTail(deltaS, couplerError)
            else -> {
                val path = SolvePath(prevCar.activeTailBogie.activeLocation.peekCurve(-tailBogieSearchDistance * 2))
                solveDistance(path.length, tailBogieSearchDistance, COUPLER_TOLERANCE,
                    { distance -> calculatePositionByTailBogie(path.locationAt(distance).reversed) },
                    couplerError
                ) ?: throw BogieException(activeTailBogie)
            }
        }
        val (headBogieLocation, tailBogieLocation, carPosition, tailToHeadDirection) = intermediateResult
        geometryPhysicsDirty = true
        activeHeadBogie.updateLocation(headBogieLocation)
        activeTailBogie.updateLocation(tailBogieLocation)
        this.position = carPosition
        val carDirection = if (activeIsReversed) -tailToHeadDirection else tailToHeadDirection
        this.direction = carDirection
        this.normal = (headBogieLocation.normal + tailBogieLocation.normal).projection(carDirection)
        updateMotionStateByHead(prevCar.tailVelocity())
    }

    // 设置完转向架位置、commit前调用，更新motionState
    private fun updateMotionStateByHeadBogie() {
        motionPhysicsDirty = true
        // headBogie原始速度
        val v0 = activeHeadBogie.activeVelocity
        val headToTail = activeTailBogie.activeLocation.position - activeHeadBogie.activeLocation.position
        val tailBogieTangent = activeTailBogie.activeLocation.tangent
        // Preserve the rigid bogie chord: (v1 - v0) dot r = 0 while v1 stays on the
        // tail rail tangent. The actual chord, rather than the rendered car direction, is the
        // constraint used again below to derive angular velocity.
        val v1 = tailBogieTangent * ((v0 dot headToTail) / (tailBogieTangent dot headToTail))
        activeTailBogie.updateVelocity(v1)
        // v1 = v0 + omega x r, where r runs from the head bogie to the tail bogie.
        // With no spin around the car's longitudinal axis:
        // omega = r x (v1 - v0) / |r|^2. Using the actual chord keeps radians/second units.
        val omega = (headToTail cross (v1 - v0)) / headToTail.lengthSquared()
        val centreToHead = _position.getActive() - activeHeadBogie.activeLocation.position
        val vCar = v0 + (omega cross centreToHead)
        motionState = MotionState(vCar, omega)
    }

    // 已知
    private fun updateMotionStateByHead(headVelocity: Vector3dc) {
        // tail到head方向单位矢量
        val d = if (activeIsReversed) -_direction.getActive() else _direction.getActive()
        // head沿杆方向速度
        val vd0s = headVelocity dot d
        val headBogieTangent = activeHeadBogie.activeLocation.tangent
        // headBogie方向与car方向夹角
        val cosTheta = headBogieTangent dot d
        // 更新tailBogie的速度矢量
        val v1 = headBogieTangent * (vd0s / cosTheta)
        activeHeadBogie.updateVelocity(v1)
        updateMotionStateByHeadBogie()
    }

    /** Recomputes active car and bogie velocity without changing consist geometry. */
    internal fun updateMotionStateByHeadVelocity(headVelocity: Vector3dc) {
        updateMotionStateByHead(headVelocity)
    }

    /** Active tail velocity used to propagate motion through the consist. */
    internal fun activeTailVelocity(): Vector3dc = tailVelocity()

    /** Clears motion without requiring valid non-zero bogie geometry. */
    internal fun clearMotionState() {
        motionPhysicsDirty = true
        bogies.forEach { it.updateVelocity(Vector3d()) }
        motionState = MotionState(Vector3d(), Vector3d())
    }

    private fun tailVelocity() : Vector3dc {
        // tail到head方向单位矢量
        val d = if (activeIsReversed) -_direction.getActive() else _direction.getActive()
        val activeMotionState = if (motionPhysicsDirty) _motionState.getActive() else _motionState.get()
        val v = activeMotionState.tangentialVelocity
        val omega = activeMotionState.angularVelocity
        // v1 = v0 + \omega x d
        return v + (omega cross (-d * (length / 2)))
    }

    @MainThread
    override fun reverse() {
        isReversed = !isReversed
        bogies.forEach { it.reverse() }
    }

    /** Preserve physical/model orientation while committing precomputed operating bogie facings. */
    internal fun applyPreparedOperatingReversal(locations: List<RailLocationWithFacing>) {
        isReversed = !isReversed
        bogies.forEachIndexed { index, bogie ->
            bogie.applyPreparedOperatingReversal(locations[index])
        }
    }

    internal fun reverseFromPhysics() {
        reversedBuffer.set(!activeIsReversed)
        reversedPhysicsDirty = true
        bogies.forEach { it.reverseFromPhysics() }
    }

    /** Select physical ends before validating saved geometry; saved bogie facing is unchanged. */
    internal fun restorePersistedOperatingDirection(reversed: Boolean) {
        // Saved bogies already carry their operating rail facing. Do not reverse them again.
        isReversed = reversed
    }

    /** Restore derived car state without moving the exact persisted bogie locations. */
    internal fun restorePersistedState(reversed: Boolean) {
        isReversed = reversed
        geometryPhysicsDirty = true
        val headLocation = headBogie.location
        val tailLocation = tailBogie.location
        val headConnectorPosition = connectorPosition(headLocation)
        val tailConnectorPosition = connectorPosition(tailLocation)
        val tailToHeadDirection = (headConnectorPosition - tailConnectorPosition).normalize()
        val headOffset = if (reversed) -bogieBOffset else bogieAOffset
        position = headConnectorPosition - tailToHeadDirection * headOffset
        direction = if (reversed) -tailToHeadDirection else tailToHeadDirection
        normal = (headLocation.normal + tailLocation.normal).projection(direction)
        updateMotionStateByHeadBogie()
    }

    override fun destroy() {
        bogies.forEach { it.destroy() }
        super.destroy()
    }

    /** Publishes Core state after all workers finish reading the previous epoch. */
    internal fun publishPhysicsState() {
        bogies.forEach { it.publishPhysicsState() }
        if (geometryPhysicsDirty) {
            _position.commit()
            _direction.commit()
            _normal.commit()
            geometryPhysicsDirty = false
        }
        if (motionPhysicsDirty) {
            _motionState.commit()
            motionPhysicsDirty = false
        }
        if (reversedPhysicsDirty) {
            reversedBuffer.commit()
            reversedPhysicsDirty = false
        }
    }

    companion object {
        private const val FORWARD_SEARCH_RADIUS_METERS = 1.0
        // Keep axle anchoring error well below the final 5mm geometry budget, including reload.
        private const val AXLE_TOLERANCE = 1e-6
        private const val COUPLER_TOLERANCE = 1e-5

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

    // A standalone car has no consist-relative installation direction.
    override fun save(): SavedCarV1 = save(installedReversed = false)

    internal fun save(installedReversed: Boolean): SavedCarV1 {
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
            ),
            installedReversed = installedReversed
        )
    }
}
