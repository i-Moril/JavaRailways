package work.art1st.mineRails.basic.pathFinding

import net.jcip.annotations.NotThreadSafe
import work.art1st.mineRails.core.physics.rail.RailView
import java.util.concurrent.CopyOnWriteArraySet

@NotThreadSafe
object RailMap {
    val destinations: HashMap<RailView, CopyOnWriteArraySet<RailDestination>> = HashMap()
    val railViews: HashMap<>

    internal fun init() {

    }
}

fun RailView.addDestination(destination: RailDestination) {
    RailMap.destinations.getOrPut(this, { CopyOnWriteArraySet() }).add(destination)
}

fun removeDestination(label: String) {
    RailMap.destinations[this]?.remove(label)
}

fun hasDestination(label: String): Boolean {
    return destinations.contains(label)
}