package work.art1st.mineRails.core.physics.rail

import kotlinx.serialization.Serializable
import work.art1st.mineRails.core.api.physics.RailObject
import work.art1st.mineRails.core.control.signal.Signal
import work.art1st.mineRails.core.physics.World
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.physics.train.TrainEnd
import work.art1st.mineRails.core.util.DeserializationMissingDependencyException
import work.art1st.mineRails.core.util.PersistentFactoryWithDependency
import work.art1st.mineRails.core.util.PersistentObject
import java.util.UUID
import kotlin.math.min

class RailLocationWithFacing(
    rail: Rail,
    idxInCurveAB: Int,
    private val direction: RailView.ViewDirection,
    /* In case the deltaS is less than TOLERANCE */
    val minorDeltaS: Double = 0.0
): PersistentObject<RailLocationWithFacing.SavedRailLocationWithFacingV1>, RailLocation(rail, idxInCurveAB) {
    constructor(railView: RailView, idxInCurveAB: Int) : this(railView.rail, idxInCurveAB, railView.direction)

    val railView = rail.getView(direction)

    val arcLength
        get() = point.arcLength + minorDeltaS

    /* Create one every time. Reversing won't be used too often. */
    val reversed: RailLocationWithFacing
        get() = RailLocationWithFacing(railView.reversed, idxInCurveAB)

    /**
     * @param deltaS: >= 0
     */
    fun forward(deltaS: Double): RailLocationWithFacing? {
        var arcLength = arcLength + deltaS
        var curve = point.curve
        var railView = this.railView
        val list = ArrayList<Pair<RailView, Curve>>()
        while (true) {
            if (arcLength <= curve.totalArcLength) {
                val newPoint = curve.getPointAt(arcLength)!!
                return RailLocationWithFacing(railView.rail, newPoint.idx, railView.direction, arcLength - newPoint.arcLength)
            }
            list.add(Pair(railView, curve))
            arcLength -= curve.totalArcLength

            railView = railView.nextActiveRail ?: return null
            curve = railView.curve
        }
    }

    /**
     * 从当前位置peek distance距离
     * distance为正：向前peek
     * distance为负：向后peek
     */
    fun peekCurve(distance: Double): List<Pair<RailView, Curve>> {
        var arcLength: Double
        var curve: Curve
        var railView = this.railView
        val list = ArrayList<Pair<RailView, Curve>>()
        if (distance >= 0) {
            arcLength = distance
            curve = railView.curve.slice(point.arcLength, min(railView.curve.totalArcLength, point.arcLength + arcLength))!!
        } else {
            arcLength = -distance
            railView = railView.reversed
            val baseLength = railView.curve.totalArcLength - point.arcLength
            curve = railView.curve.slice(baseLength, min(railView.curve.totalArcLength, baseLength + arcLength))!!
        }
        while (true) {
            if (arcLength <= curve.totalArcLength) {
                list.add(Pair(railView, curve.slice(0.0, min(railView.curve.totalArcLength, arcLength))!!))
                break
            }
            list.add(Pair(railView, curve))
            arcLength -= curve.totalArcLength

            railView = railView.nextActiveRail ?: break
            curve = railView.curve
        }
        return list
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
            val railView = x.first
            for (s in railView.staticRailObjects) {
                val arcLength = s.location.arcLengthIn(direction)
                if (arcLength > maxDistance) {
                    break
                }
                if (arcLength > minDistance) {
                    list.add(s)
                }
            }
            for (s in railView.dynamicRailObjects) {
                val arcLength = s.location.arcLengthIn(direction)
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
            val railView = x.first
            for (s in railView.staticRailObjects) {
                val arcLength = s.location.arcLengthIn(direction)
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
            val railView = x.first
            for (s in railView.dynamicRailObjects) {
                val arcLength = s.location.arcLengthIn(direction)
                if (minDistance < arcLength && arcLength <=maxDistance) {
                    list.add(s)
                }
            }
            maxDistance -= railView.curve.totalArcLength
            minDistance = -1.0   // 只有第一个循环会用到minDistance
        }
        return list
    }

    override fun equals(other: Any?): Boolean {
        if (other is RailLocationWithFacing) {
            return other.rail == rail && idxInCurveAB == other.idxInCurveAB && direction == other.direction
        }
        return false
    }

    override fun hashCode(): Int {
        var result = rail.hashCode()
        result = 31 * result + idxInCurveAB.hashCode()
        result = 31 * result + direction.hashCode()
        return result
    }

    @Serializable
    data class SavedRailLocationWithFacingV1(
        val railId: String,
        val idxInCurveAB: Int,
        val direction: RailView.ViewDirection,
        /* In case the deltaS is less than TOLERANCE */
        val minorDeltaS: Double = 0.0
    )

    override fun save(): SavedRailLocationWithFacingV1 = SavedRailLocationWithFacingV1(
        rail.id.toString(),
        idxInCurveAB,
        direction,
        minorDeltaS
    )

    companion object Deserializer : PersistentFactoryWithDependency<RailLocationWithFacing, SavedRailLocationWithFacingV1, World> {
        override fun load(saved: SavedRailLocationWithFacingV1, dependencyProvider: World): RailLocationWithFacing {
            val id = UUID.fromString(saved.railId)
            val rail = dependencyProvider.getRail(id)
                ?: throw DeserializationMissingDependencyException(Rail.javaClass, id, dependencyProvider)
            return RailLocationWithFacing(rail, saved.idxInCurveAB, saved.direction, saved.minorDeltaS)
        }
    }
}
