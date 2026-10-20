package work.art1st.javarailways.basic.rail

import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.RailView

/** Topology-derived turnout invariants shared by authoring operations. */
object TurnoutTopology {
    fun isTurnoutEndpoint(viewToEndpoint: RailView): Boolean =
        viewToEndpoint.nextRails.size > 1 ||
            viewToEndpoint.nextRails.any { nextAway -> nextAway.reversed.nextRails.size > 1 }

    fun hasTurnoutEndpoint(rail: CoreRail): Boolean =
        isTurnoutEndpoint(rail.viewBA) || isTurnoutEndpoint(rail.viewAB)

    /** Complete three-leg turnout closure for every turnout endpoint touched by [rails]. */
    fun turnoutRailsTouching(rails: Collection<CoreRail>): Set<CoreRail> = rails.asSequence()
        .flatMap { rail -> sequenceOf(rail.viewBA, rail.viewAB) }
        .flatMap(::turnoutViewsAt)
        .mapTo(linkedSetOf()) { it.rail }

    private fun turnoutViewsAt(viewToEndpoint: RailView): Sequence<RailView> {
        if (!isTurnoutEndpoint(viewToEndpoint)) return emptySequence()
        val stemToEndpoint = if (viewToEndpoint.nextRails.size > 1) {
            viewToEndpoint
        } else {
            viewToEndpoint.nextRails.asSequence()
                .mapNotNull { it.reversed as? RailView }
                .firstOrNull { candidate ->
                    candidate.nextRails.size > 1 &&
                        candidate.nextRails.any { nextAway -> nextAway.reversed === viewToEndpoint }
                }
                ?: return emptySequence()
        }
        return sequence {
            yield(stemToEndpoint)
            stemToEndpoint.nextRails.forEach { nextAway ->
                (nextAway.reversed as? RailView)?.let { yield(it) }
            }
        }
    }

}
