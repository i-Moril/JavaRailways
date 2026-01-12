package work.art1st.mineRails.core.physics.rail

import work.art1st.mineRails.core.control.signal.Signal
import work.art1st.mineRails.core.physics.rail.curve.Curve
import work.art1st.mineRails.core.physics.train.TrainEnd

class RailLocationWithFacing(
    rail: Rail,
    idxInCurveAB: Int,
    private val direction: RailView.ViewDirection,
    /* In case the deltaS is less than TOLERANCE */
    val minorDeltaS: Double = 0.0
): RailLocation(rail, idxInCurveAB) {
    constructor(railView: RailView, idxInCurveAB: Int) : this(railView.rail, idxInCurveAB, railView.direction)

    val railView = rail.getView(direction)

    val arcLength
        get() = point.arcLength + minorDeltaS

    /* Create one every time. Reversing won't be used too often. */
    val reversed: RailLocationWithFacing
        get() = RailLocationWithFacing(railView.reversed, idxInCurveAB)

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
            arcLength = point.arcLength + distance
            curve = point.curve
        } else {
            arcLength = point.arcLength + distance
            railView = railView.reversed
            curve = railView.curve.slice(railView.curve.totalArcLength - arcLength, railView.curve.totalArcLength)
        }
        while (true) {
            if (arcLength <= curve.totalArcLength) {
                list.add(Pair(railView, curve.slice(0.0, arcLength)))
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
}
