package work.art1st.mineRails.core.physics.rail

import kotlinx.serialization.Serializable
import work.art1st.mineRails.core.control.signal.Signal
import work.art1st.mineRails.core.api.physics.exception.InvalidActiveRailException
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.api.physics.curve.PrimeCurve
import work.art1st.mineRails.core.api.physics.exception.CannotConnectRailException
import work.art1st.mineRails.core.api.physics.rail.ViewDirection
import work.art1st.mineRails.core.api.physics.rail.RailView as ApiRailView
import work.art1st.mineRails.core.physics.train.TrainEnd
import kotlin.properties.Delegates

class RailView(
    override val rail: CoreRail,
    override val viewDirection: ViewDirection,
    override val curve: PrimeCurve,
    var mutableNextRails: MutableList<RailView>,
    nextActiveRail: RailView? = if (mutableNextRails.isEmpty()) null else mutableNextRails[0]
) : ApiRailView {
    init {
        if (nextActiveRail != null && (mutableNextRails.isEmpty() || !mutableNextRails.contains(nextActiveRail))) {
            throw InvalidActiveRailException(this, nextActiveRail)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override val nextRails: List<ApiRailView>
        get() = mutableNextRails as List<ApiRailView>

    var activeRail: RailView? by Delegates.vetoable(nextActiveRail) {
        property, oldValue, newValue ->
        if (newValue == null || mutableNextRails.contains(newValue)) true else
            throw InvalidActiveRailException(this, newValue)
    }

    override val nextActiveRail: ApiRailView?
        get() = activeRail

    // 曲线起点（弧长 0）
    override val startPoint: Curve.Point
        get() = curve.getPointAt(0.0)

    // 曲线终点（弧长 totalArcLength）
    override val endPoint: Curve.Point
        get() = curve.getPointAt(curve.totalArcLength)

    val staticRailObjects: Collection<Signal>
        get() = when (viewDirection) {
            ViewDirection.AB -> rail.signals
            ViewDirection.BA -> rail.signals.reversed()
        }

    val dynamicRailObjects: Collection<TrainEnd>
        get() = rail.trains

    override val reversed: RailView
        get() = when (viewDirection) {
            ViewDirection.AB -> rail.viewBA
            ViewDirection.BA -> rail.viewAB
        }

    override fun selectNextRail(nextRail: ApiRailView?) {
        activeRail = nextRail?.let {
            it as? RailView ?: throw IllegalArgumentException("nextRail must be a Core RailView")
        }
    }

    @Serializable
    data class SavedRailViewV1(
        val rail: String,
        val direction: ViewDirection,
        val nextRails: List<String>,
        val nextActiveRail: Int
    )

    fun save() : SavedRailViewV1 {
        return SavedRailViewV1(
            rail.id.toString(),
            viewDirection,
            mutableNextRails.map { it.rail.id.toString() },
            mutableNextRails.indexOf(activeRail),
        )
    }

    // 根据弧长获取 RailLocationWithFacing（唯一的公共入口）
    override fun getRailLocationAt(arcLength: Double) : RailLocationWithFacing {
        require(arcLength in 0.0..curve.totalArcLength)
        return RailLocationWithFacing(this, arcLength)
    }

    companion object {
        /**
         * I ---> J <--- K
         * 然后连接上新的轨道
         */
        @Throws(CannotConnectRailException::class)
        fun connect(viewIJ: RailView, viewKJ: RailView) {
            val j1 = viewIJ.endPoint.position
            val j2 = viewKJ.endPoint.position
            if (j1.distance(j2) > Curve.TOLERANCE) {
                throw CannotConnectRailException()
            }
            val ijNext = viewKJ.reversed
            val kjNext = viewIJ.reversed
            if (!viewIJ.mutableNextRails.contains(ijNext)) {
                viewIJ.mutableNextRails.add(ijNext)
            }
            viewIJ.activeRail = ijNext
            if (!viewKJ.mutableNextRails.contains(kjNext)) {
                viewKJ.mutableNextRails.add(kjNext)
            }
            viewKJ.activeRail = kjNext
        }
    }
}
