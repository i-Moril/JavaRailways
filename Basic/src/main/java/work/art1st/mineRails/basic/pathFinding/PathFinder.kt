package work.art1st.mineRails.basic.pathFinding

class PathFinder {


    fun addDestination(destination: RailDestination) {
        destinations[destination.label] = destination
    }

    fun removeDestination(label: String) {
        destinations.remove(label)
    }

    fun hasDestination(label: String): Boolean {
        return destinations.contains(label)
    }

    fun findPath(label: String): List<RailView>? {
        val path = LinkedList<RailView>()
        val pending = PriorityQueue<Triple<RailView, Double, RailView?>> {
                a, b -> if (a.second < b.second) -1 else if (a.second == b.second) 0 else 1
        }
        val visited = HashMap<RailView, RailView?>()
        pending.add(Triple(this, 0.0, null))
        while (pending.isNotEmpty()) {
            val current = pending.poll()
            visited[current.first] = current.third
            if (current.first.hasDestination(label)) {
                var node: RailView? = current.first
                while (node != null) {
                    path.add(node)
                    node = visited[node]
                }
                return path
            }
            val distance = current.second + current.first.curve.totalArcLength
            current.first.nextRails.forEach { rail -> if (!visited.contains(rail)) pending.add(Triple(rail, distance, current.first)) }
            while (visited.contains(pending.first().first)) {
                pending.poll()
            }
        }
        return null
    }

    /* TODO: 异步关系 */
    fun asyncFindPath(label: String, then: (List<RailView>?) -> Unit) {
//        rail.world.scheduler.execute {
//            val path = findPath(label)
//            then(path)
//        }
    }
}