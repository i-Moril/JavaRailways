package work.art1st.mineRails.basic.pathFinding

import work.art1st.mineRails.core.api.physics.rail.RailView
import java.util.LinkedList

/**
 * 路径查找器，基于 [RailMap] 中的目的地索引。
 *
 * 搜索策略：双向 BFS，同时沿 nextRails 和 reversed 搜索。
 *
 * 线程不安全，使用前需确保同一 [RailMap] 不会并发访问。
 */
class PathFinder(
    private val railMap: RailMap
) {
    /**
     * 从指定的起始 [RailView] 出发，查找通往标签为 [label] 的目的地的路径。
     * @return 路径经过的所有 [RailView]（从起点到终点），若不存在则返回 null。
     */
    fun findPath(start: RailView, label: String): List<RailView>? {
        fun allows(view: RailView): Boolean =
            view.rail.metadata.allowedDirection?.let { it == view.viewDirection } ?: true
        if (!allows(start)) return null
        val pending: ArrayDeque<State> = ArrayDeque()
        // 记录当前 view 来自哪个 view（用于路径重建）
        val predecessor = HashMap<RailView, RailView?>()

        pending.addLast(State(start, 0))
        predecessor[start] = null

        while (pending.isNotEmpty()) {
            val current: State = pending.removeFirst()

            if (railMap.hasDestination(current.view, label)) {
                return rebuildPath(predecessor, current.view)
            }

            val neighbors = mutableListOf<RailView>()
            // 沿 nextRails 前进（顺向搜索）
            for (next in current.view.nextRails) {
                if (allows(next) && !predecessor.containsKey(next)) {
                    neighbors.add(next)
                }
            }
            // 反向（同 rail 的另一个方向）
            val reversed = current.view.reversed
            if (allows(reversed) && !predecessor.containsKey(reversed)) {
                neighbors.add(reversed)
            }

            for (next in neighbors) {
                pending.addLast(State(next, current.depth + 1))
                predecessor[next] = current.view
            }
        }
        return null
    }

    private data class State(val view: RailView, val depth: Int)

    private fun rebuildPath(predecessor: Map<RailView, RailView?>, goal: RailView): List<RailView> {
        val path = LinkedList<RailView>()
        var node: RailView? = goal
        while (node != null) {
            path.addFirst(node)
            node = predecessor[node]
        }
        return path
    }
}