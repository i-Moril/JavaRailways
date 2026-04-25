package work.art1st.mineRails.core.physics.rail

import kotlinx.serialization.Serializable
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.physics.rail.RailLocation
import work.art1st.mineRails.core.api.physics.rail.RailObject
import work.art1st.mineRails.core.control.signal.Signal
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.api.util.exception.CorruptedDataException
import work.art1st.mineRails.core.physics.rail.curve.inReversedCurve
import work.art1st.mineRails.core.physics.rail.curve.reverseArcLength
import work.art1st.mineRails.core.physics.train.TrainEnd
import work.art1st.configLib.persistence.Loads
import work.art1st.configLib.persistence.PersistentBy
import java.util.UUID
import kotlin.math.min

@ConsistentCopyVisibility
data class RailLocationWithFacing private constructor(
    val railView: RailView,
    private val point: Curve.Point,
    /* In case the deltaS is less than TOLERANCE */
    val minorDeltaS: Double = 0.0
): RailLocation, PersistentBy<RailLocationWithFacing.SavedRailLocationWithFacingV1> {
    constructor(railView: RailView, idxInRailView: Int, minorDeltaS: Double = 0.0) : this(
        railView,
        railView.curve.getPointAt(idxInRailView),
        minorDeltaS
    )

    override val rail = railView.rail

    override val position
        get() = point.position

    override val tangent
        get() = point.tangent

    override val normal
        get() = point.normal

    val arcLength
        get() = point.arcLength + minorDeltaS

    val facing
        get() = railView.viewDirection

    override fun arcLengthIn(facing: RailView.ViewDirection): Double {
        return if (facing == this.facing) arcLength else reverseArcLength(arcLength, railView.curve)
    }

    /* Create one every time. Reversing won't be used too often. */
    val reversed: RailLocationWithFacing
        get() = RailLocationWithFacing(railView.reversed, point.inReversedCurve, -minorDeltaS)

    /**
     * @param deltaS: >= 0
     */

    fun forward(deltaS: Double): RailLocationWithFacing? {
        require(deltaS >= 0) {
            "Delta must be greater than zero."
        }
        var arcLength = arcLength + deltaS
        var curve = point.curve
        var railView = this.railView
        val list = ArrayList<Pair<RailView, Curve>>()
        while (true) {
            if (arcLength <= curve.totalArcLength) {
                val newPoint = curve.getPointAt(arcLength)
                return RailLocationWithFacing(railView, newPoint.idx, arcLength - newPoint.arcLength)
            }
            list.add(Pair(railView, curve))
            arcLength -= curve.totalArcLength

            railView = railView.nextActiveRail ?: return null
            curve = railView.curve
        }
    }

    /**
     * 从当前位置peek distance距离
     * distance为正：向前peek, 方向相同
     * distance为负：向后peek, 方向相反
     */
    fun peekCurve(distance: Double): RailSegments {
        var arcLength: Double
        var segment: RailSegment
        var railView = this.railView
        val list: MutableList<RailSegment> = mutableListOf()
        val baseLength: Double
        if (distance >= 0) {
            arcLength = distance
            baseLength = arcLength
        } else {
            arcLength = -distance
            railView = railView.reversed
            baseLength = railView.curve.totalArcLength - point.arcLength
        }
        segment = RailSegment(railView, baseLength, min(railView.curve.totalArcLength, baseLength + arcLength))
        while (true) {
            if (arcLength <= segment.totalArcLength) {
                list.add(RailSegment(segment, 0.0, min(railView.curve.totalArcLength, arcLength)))
                break
            }
            list.add(segment)
            arcLength -= segment.totalArcLength

            railView = railView.nextActiveRail ?: break
            segment = RailSegment(railView)
        }
        return RailSegments(list)
    }

    /**
     * 检查前方的物体
     * distance > 0
     */
    fun peekRailObjects(distance: Double): List<RailObject> {
        val list = ArrayList<RailObject>()
        val rails = peekCurve(distance)
        var maxDistance = distance
        var minDistance = arcLength
        // 挨个检查RailObject相对的arcLength
        for (x in rails) {
            val railView = x.railView
            for (s in railView.staticRailObjects) {
                val arcLength = s.location.arcLengthIn(facing)
                if (arcLength > maxDistance) {
                    break
                }
                if (arcLength > minDistance) {
                    list.add(s)
                }
            }
            for (s in railView.dynamicRailObjects) {
                val arcLength = s.location.arcLengthIn(facing)
                if (minDistance < arcLength && arcLength <=maxDistance) {
                    list.add(s)
                }
            }
            maxDistance -= railView.curve.totalArcLength
            minDistance = -1.0   // 只有第一个循环会用到minDistance
        }
        return list
    }

    fun peekSignals(distance: Double): List<Signal> {
        val list = ArrayList<Signal>()
        val rails = peekCurve(distance)
        var maxDistance = distance
        var minDistance = arcLength
        // 挨个检查RailObject相对的arcLength
        for (x in rails) {
            val railView = x.railView
            for (s in railView.staticRailObjects) {
                val arcLength = s.location.arcLengthIn(facing)
                if (arcLength > maxDistance) {
                    break
                }
                if (arcLength > minDistance) {
                    list.add(s)
                }
            }
            maxDistance -= railView.curve.totalArcLength
            minDistance = -1.0   // 只有第一个循环会用到minDistance
        }
        return list
    }

    fun peekTrains(distance: Double): List<TrainEnd> {
        val list = ArrayList<TrainEnd>()
        val rails = peekCurve(distance)
        var maxDistance = distance
        var minDistance = arcLength
        // 挨个检查RailObject相对的arcLength
        for (x in rails) {
            val railView = x.railView
            for (s in railView.dynamicRailObjects) {
                val arcLength = s.location.arcLengthIn(facing)
                if (minDistance < arcLength && arcLength <=maxDistance) {
                    list.add(s)
                }
            }
            maxDistance -= railView.curve.totalArcLength
            minDistance = -1.0   // 只有第一个循环会用到minDistance
        }
        return list
    }

    @Serializable
    data class SavedRailLocationWithFacingV1(
        val railId: String,
        val idxInCurveAB: Int,
        val direction: RailView.ViewDirection,
        /* In case the deltaS is less than TOLERANCE */
        val minorDeltaS: Double = 0.0
    ) : Loads<RailLocationWithFacing, World> {
        @Throws(CorruptedDataException::class)
        override fun load(dependency: World): RailLocationWithFacing {
            val rail = dependency.getRail(UUID.fromString(railId)) ?: throw CorruptedDataException(this)
            val point = rail.viewAB.getRailLocationAt(idxInCurveAB).point
            return RailLocationWithFacing(rail.getView(direction), point, minorDeltaS)
        }
    }

    override fun save(): SavedRailLocationWithFacingV1 {
        return SavedRailLocationWithFacingV1(rail.id.toString(), point.idx, facing, minorDeltaS)
    }

//    fun save(): SavedRailLocationWithFacingV1 = SavedRailLocationWithFacingV1(
//        rail.id.toString(),
//        idxInCurveAB,
//        facing,
//        minorDeltaS
//    )
//
//    companion object Deserializer : PersistentFactoryWithDependency<RailLocationWithFacing, SavedRailLocationWithFacingV1, World> {
//        override fun load(saved: SavedRailLocationWithFacingV1, dependencyProvider: World): RailLocationWithFacing {
//            val id = UUID.fromString(saved.railId)
//            val rail = dependencyProvider.getRail(id)
//                ?: throw DeserializationMissingDependencyException(Rail.javaClass, id, dependencyProvider)
//            return RailLocationWithFacing(rail, saved.idxInCurveAB, saved.direction, saved.minorDeltaS)
//        }
//    }
}