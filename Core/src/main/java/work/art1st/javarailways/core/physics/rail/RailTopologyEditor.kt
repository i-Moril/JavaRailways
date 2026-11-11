package work.art1st.javarailways.core.physics.rail

import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.control.signal.Signal
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef

/** Shared split/merge values and validation used by the prepared RailWand edit pipeline. */
object RailTopologyEditor {
    data class SplitResult(
        val before: CoreRail,
        val after: CoreRail,
        val operationId: String
    )

    fun mergeEligibilityError(
        before: CoreRail,
        after: CoreRail,
        signalCanBeRebuilt: (Signal) -> Boolean = { false }
    ): String? {
        if (before.world !== after.world) return "轨道不在同一世界"
        if (before === after) return "不能合并同一轨道"
        val affectedRailIds = buildSet {
            add(before.id)
            add(after.id)
            before.viewBA.joinedEndpoints.forEach { add(it.railId) }
            after.viewAB.joinedEndpoints.forEach { add(it.railId) }
        }
        if (before.world.occupyingTrainIds(affectedRailIds).isNotEmpty()) return "不能合并有列车占用的相关轨道"
        if (before.signals.any { !signalCanBeRebuilt(it) } ||
            after.signals.any { !signalCanBeRebuilt(it) }
        ) return "不能合并包含不可重建信号机的轨道"
        if (before.viewAB.mutableNextRails != listOf(after.viewAB) ||
            after.viewBA.mutableNextRails != listOf(before.viewBA) ||
            before.viewAB.joinedEndpoints != setOf(RailEndpointRef(after.id, RailEndpoint.A)) ||
            after.viewBA.joinedEndpoints != setOf(RailEndpointRef(before.id, RailEndpoint.B))
        ) return "分割锚点还连接了其他轨道"
        if (before.viewAB.endPoint.position.distance(after.viewBA.endPoint.position) > Curve.TOLERANCE) {
            return "分割锚点两侧端点不重合"
        }
        return null
    }
}
