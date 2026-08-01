package work.art1st.javarailways.basic.route

import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.api.physics.rail.RailView
import work.art1st.javarailways.core.api.physics.rail.ViewDirection

/** Direction-independent physical lock intervals on every leg around one turnout anchor. */
fun TurnoutRequirement.lockingIntervals(world: World): List<RailInterval> {
    val fromView = requireNotNull(world.getRail(from.railId)?.getView(from.direction)) {
        "turnout source rail disappeared: ${from.railId}"
    }
    val lock = lockingDistanceMeters
    return buildList {
        add(endpointInterval(fromView, atStart = false, lock))
        fromView.nextRails.forEach { branch ->
            add(endpointInterval(branch, atStart = true, lock))
        }
    }.distinct()
}

private fun endpointInterval(view: RailView, atStart: Boolean, distanceMeters: Double): RailInterval {
    val length = view.rail.getView(ViewDirection.AB).curve.totalArcLength
    val startInAb = when {
        view.viewDirection == ViewDirection.AB && atStart -> 0.0
        view.viewDirection == ViewDirection.AB -> (length - distanceMeters).coerceAtLeast(0.0)
        view.viewDirection == ViewDirection.BA && atStart ->
            (length - distanceMeters).coerceAtLeast(0.0)
        else -> 0.0
    }
    val endInAb = when {
        view.viewDirection == ViewDirection.AB && atStart -> distanceMeters.coerceAtMost(length)
        view.viewDirection == ViewDirection.AB -> length
        view.viewDirection == ViewDirection.BA && atStart -> length
        else -> distanceMeters.coerceAtMost(length)
    }
    return RailInterval(view.rail.id, startInAb, endInAb)
}
