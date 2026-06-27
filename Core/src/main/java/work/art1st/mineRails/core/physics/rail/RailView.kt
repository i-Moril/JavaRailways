package work.art1st.mineRails.core.physics.rail

import kotlinx.serialization.Serializable
import work.art1st.mineRails.core.control.signal.Signal
import work.art1st.mineRails.core.api.physics.exception.InvalidActiveRailException
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.api.physics.curve.PrimeCurve
import work.art1st.mineRails.core.api.physics.exception.CannotConnectRailException
import work.art1st.mineRails.core.physics.train.TrainEnd
import kotlin.properties.Delegates

class RailView(
    val rail: CoreRail,
    val viewDirection: ViewDirection,
    val curve: PrimeCurve,
    var nextRails: MutableList<RailView>,
    nextActiveRail: RailView? = if (nextRails.isEmpty()) null else nextRails[0]
) {
    init {
        if ((nextActiveRail != null && nextRails.isEmpty()) || (!nextRails.isEmpty() && !nextRails.contains(nextActiveRail))) {
            throw InvalidActiveRailException(this, nextActiveRail)
        }
    }

    var nextActiveRail: RailView? by Delegates.vetoable(nextActiveRail) {
        property, oldValue, newValue ->
        if (nextRails.contains(newValue)) true else
            throw InvalidActiveRailException(this, newValue)
    }

    enum class ViewDirection {
        AB,
        BA
    }

    // 曲线起点（弧长 0）
    val startPoint: Curve.Point
        get() = curve.getPointAt(0.0)

    // 曲线终点（弧长 totalArcLength）
    val endPoint: Curve.Point
        get() = curve.getPointAt(curve.totalArcLength)

    val staticRailObjects: Collection<Signal>
        get() = when (viewDirection) {
            ViewDirection.AB -> rail.signals
            ViewDirection.BA -> rail.signals.reversed()
        }

    val dynamicRailObjects: Collection<TrainEnd>
        get() = rail.trains

    val reversed
        get() = when (viewDirection) {
            ViewDirection.AB -> rail.viewBA
            ViewDirection.BA -> rail.viewAB
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
            nextRails.map { railView -> rail.id.toString() },
            nextRails.indexOf(nextActiveRail),
        )
    }

    // 根据弧长获取 RailLocationWithFacing（唯一的公共入口）
    fun getRailLocationAt(arcLength: Double) : RailLocationWithFacing {
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
            if (j1.distance(j2) > 1e-10) {
                throw CannotConnectRailException()
            }
            viewIJ.nextRails.add(viewKJ.reversed)
            viewIJ.nextActiveRail = viewKJ.reversed
            viewKJ.nextRails.add(viewIJ.reversed)
            viewKJ.nextActiveRail = viewIJ.reversed
        }
    }

//    companion object Deserializer : PersistentFactoryWithDependency<RailView, SavedRailViewV1, World> {
//        override fun load(
//            saved: SavedRailViewV1,
//            dependencyProvider: World
//        ): RailView {
//            val rail = dependencyProvider.getRail(UUID.fromString(saved.rail))
//                            ?: throw DeserializationMissingDependencyException(Rail.javaClass, saved.rail, dependencyProvider)
//            val nextRails = saved.nextRails.stream().map {
//                id -> dependencyProvider.getRail(UUID.fromString(id))
//                    ?: throw DeserializationMissingDependencyException(Rail.javaClass, id, dependencyProvider)
//            }.collect(Collectors.toList())
//            val nextActiveRail = nextRails[saved.nextActiveRail]
//            return RailView(
//                rail,
//                saved.direction,
//                nextRails,
//                nextActiveRail
//            )
//        }
//    }
}
