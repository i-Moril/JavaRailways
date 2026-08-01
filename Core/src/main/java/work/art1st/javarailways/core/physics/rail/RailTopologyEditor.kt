package work.art1st.javarailways.core.physics.rail

import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.RailSplitMetadata
import work.art1st.javarailways.core.api.util.annotation.MainThread
import java.util.UUID

/** Atomic main-thread topology replacements used by rail editing tools. */
object RailTopologyEditor {
    data class SplitResult(
        val before: CoreRail,
        val after: CoreRail,
        val operationId: String
    )

    private data class ViewSnapshot(
        val view: RailView,
        val next: List<RailView>,
        val active: RailView?
    )

    @MainThread
    fun splitWithPreparedCurves(
        rail: CoreRail,
        beforeCurve: PrimeCurve,
        afterCurve: PrimeCurve,
        operationId: String = UUID.randomUUID().toString()
    ): SplitResult {
        require(operationId.isNotBlank())
        require(rail.world.getRail(rail.id) === rail) { "Rail is no longer registered in its world" }
        require(rail.trains.isEmpty()) { "Cannot split a rail occupied by trains" }
        require(rail.signals.isEmpty()) { "Cannot split a rail containing signals" }
        require(beforeCurve.totalArcLength > Curve.TOLERANCE && afterCurve.totalArcLength > Curve.TOLERANCE) {
            "Split point is too close to a rail endpoint"
        }
        require(beforeCurve.getPointAt(0.0).position.distance(rail.curve.getPointAt(0.0).position) <= Curve.TOLERANCE)
        require(afterCurve.getPointAt(afterCurve.totalArcLength).position.distance(
            rail.curve.getPointAt(rail.curve.totalArcLength).position
        ) <= Curve.TOLERANCE)
        require(beforeCurve.getPointAt(beforeCurve.totalArcLength).position.distance(
            afterCurve.getPointAt(0.0).position
        ) <= Curve.TOLERANCE)

        val world = rail.world
        val existingRails = world.allRails.filter { it !== rail }
        val snapshots = (existingRails + rail).flatMap(::snapshotViews)
        val parentLineage = rail.metadata.createdBySplit
        val beforeMetadata = rail.metadata.copy(
            createdBySplit = RailSplitMetadata(operationId, 0, parentLineage)
        )
        val afterMetadata = rail.metadata.copy(
            createdBySplit = RailSplitMetadata(operationId, 1, parentLineage)
        )
        val before = CoreRail(world, beforeCurve, mutableListOf(), mutableListOf(), beforeMetadata)
        val after = CoreRail(world, afterCurve, mutableListOf(), mutableListOf(), afterMetadata)

        try {
            val replacements = mapOf(
                rail.viewAB to before.viewAB,
                rail.viewBA to after.viewBA
            )
            setConnections(
                before.viewBA,
                rail.viewBA.mutableNextRails.map { replacements[it] ?: it },
                rail.viewBA.activeRail?.let { replacements[it] ?: it }
            )
            setConnections(before.viewAB, listOf(after.viewAB), after.viewAB)
            setConnections(after.viewBA, listOf(before.viewBA), before.viewBA)
            setConnections(
                after.viewAB,
                rail.viewAB.mutableNextRails.map { replacements[it] ?: it },
                rail.viewAB.activeRail?.let { replacements[it] ?: it }
            )
            replaceReferences(existingRails, replacements)
        } catch (error: Throwable) {
            restoreViews(snapshots)
            removeReplacement(before)
            removeReplacement(after)
            throw error
        }

        rail.destroy()
        world.removeRail(rail)
        return SplitResult(before, after, operationId)
    }

    fun mergeEligibilityError(before: CoreRail, after: CoreRail): String? {
        if (before.world !== after.world) return "轨道不在同一世界"
        if (before === after) return "不能合并同一轨道"
        val beforeSplit = before.metadata.createdBySplit ?: return "前段不是由分割创建"
        val afterSplit = after.metadata.createdBySplit ?: return "后段不是由分割创建"
        if (beforeSplit.operationId != afterSplit.operationId ||
            beforeSplit.segmentIndex != 0 || afterSplit.segmentIndex != 1
        ) return "两段轨道不是同一次分割产生的相邻段"
        if (beforeSplit.parent != afterSplit.parent) return "分割谱系不一致"
        if (before.metadata.copy(createdBySplit = beforeSplit.parent) !=
            after.metadata.copy(createdBySplit = afterSplit.parent)
        ) return "两段轨道元数据不一致"
        if (before.trains.isNotEmpty() || after.trains.isNotEmpty()) return "不能合并有列车占用的轨道"
        if (before.signals.isNotEmpty() || after.signals.isNotEmpty()) return "不能合并包含信号机的轨道"
        if (before.viewAB.mutableNextRails != listOf(after.viewAB) ||
            after.viewBA.mutableNextRails != listOf(before.viewBA)
        ) return "分割锚点还连接了其他轨道"
        if (before.viewAB.endPoint.position.distance(after.viewBA.endPoint.position) > Curve.TOLERANCE) {
            return "分割锚点两侧端点不重合"
        }
        return null
    }

    @MainThread
    fun mergeWithPreparedCurve(before: CoreRail, after: CoreRail, mergedCurve: PrimeCurve): CoreRail {
        val eligibilityError = mergeEligibilityError(before, after)
        require(eligibilityError == null) { eligibilityError ?: "Rails cannot be merged" }
        require(before.world.getRail(before.id) === before && after.world.getRail(after.id) === after) {
            "Rail changed before merge"
        }
        require(mergedCurve.getPointAt(0.0).position.distance(before.viewAB.startPoint.position) <= Curve.TOLERANCE)
        require(mergedCurve.getPointAt(mergedCurve.totalArcLength).position.distance(after.viewAB.endPoint.position) <= Curve.TOLERANCE)

        val world = before.world
        val existingRails = world.allRails.filter { it !== before && it !== after }
        val snapshots = (existingRails + before + after).flatMap(::snapshotViews)
        val lineage = requireNotNull(before.metadata.createdBySplit)
        val mergedMetadata = before.metadata.copy(createdBySplit = lineage.parent)
        val merged = CoreRail(world, mergedCurve, mutableListOf(), mutableListOf(), mergedMetadata)

        try {
            val replacements = mapOf(
                before.viewAB to merged.viewAB,
                after.viewBA to merged.viewBA
            )
            setConnections(
                merged.viewBA,
                before.viewBA.mutableNextRails.map { replacements[it] ?: it },
                before.viewBA.activeRail?.let { replacements[it] ?: it }
            )
            setConnections(
                merged.viewAB,
                after.viewAB.mutableNextRails.map { replacements[it] ?: it },
                after.viewAB.activeRail?.let { replacements[it] ?: it }
            )
            replaceReferences(existingRails, replacements)
        } catch (error: Throwable) {
            restoreViews(snapshots)
            removeReplacement(merged)
            throw error
        }

        before.destroy()
        after.destroy()
        world.removeRail(before)
        world.removeRail(after)
        return merged
    }

    private fun snapshotViews(rail: CoreRail): List<ViewSnapshot> = listOf(
        ViewSnapshot(rail.viewAB, rail.viewAB.mutableNextRails.toList(), rail.viewAB.activeRail),
        ViewSnapshot(rail.viewBA, rail.viewBA.mutableNextRails.toList(), rail.viewBA.activeRail)
    )

    private fun restoreViews(snapshots: List<ViewSnapshot>) {
        snapshots.forEach { setConnections(it.view, it.next, it.active) }
    }

    private fun replaceReferences(rails: List<CoreRail>, replacements: Map<RailView, RailView>) {
        rails.flatMap(::views).forEach { view ->
            val next = view.mutableNextRails.map { replacements[it] ?: it }
            val active = view.activeRail?.let { replacements[it] ?: it }
            setConnections(view, next, active)
        }
    }

    private fun views(rail: CoreRail): List<RailView> = listOf(rail.viewAB, rail.viewBA)

    private fun setConnections(view: RailView, next: List<RailView>, active: RailView?) {
        view.activeRail = null
        view.mutableNextRails.clear()
        view.mutableNextRails.addAll(next.distinct())
        if (active != null && active in view.mutableNextRails) {
            runCatching { view.activeRail = active }
        }
    }

    private fun removeReplacement(rail: CoreRail) {
        rail.destroy()
        rail.world.removeRail(rail)
    }
}
