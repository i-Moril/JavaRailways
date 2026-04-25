package work.art1st.mineRails.basic.pathFinding

import work.art1st.mineRails.core.physics.rail.RailView
import java.util.LinkedList
import java.util.PriorityQueue

/**
 * A* 路径查找器，基于 [RailMap] 中的目的地索引。
 * 线程不安全，使用前需确保同一 [RailMap] 不会并发访问。
 */
class PathFinder(
    private val railMap: RailMap
) {
    /**
     * 从指定的起始 [RailView] 出发，查找通往标签为 [label] 的目的地的最短路径。
     * @return 路径经过的所有 [RailView]（从起点到终点），若不存在则返回 null。
     */
    fun findPath(start: RailView, label: String): List<RailView>? {
        val path = LinkedList<RailView>()
        val pending = PriorityQueue<Triple<RailView, Double, RailView?>> { a, b ->
            when {
                a.second < b.second -> -1
                a.second > b.second -> 1
                else -> 0
            }
        }
        val visited = HashMap<RailView, RailView?>()

        pending.add(Triple(start, 0.0, null))

        while (pending.isNotEmpty()) {
            val current = pending.poll()
            val currentView = current.first

            if (visited.containsKey(currentView)) {
                continue
            }

            visited[currentView] = current.third

            if (railMap.hasDestination(label)) {
                var node: RailView? = currentView
                while (node != null) {
                    path.addFirst(node)
                    node = visited[node]
                }
                return path
            }

            val distance = current.second + currentView.curve.totalArcLength
            currentView.nextRails.forEach { rail ->
                if (!visited.containsKey(rail)) {
                    pending.add(Triple(rail, distance, currentView))
                }
            }
        }
        return null
    }
}
