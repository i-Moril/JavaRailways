package work.art1st.javarailways.basic.rail

import work.art1st.javarailways.core.api.physics.curve.Curve
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

    fun splitLockingClearanceError(
        rail: CoreRail,
        splitArcLengthInAbMeters: Double,
        lockingDistanceMeters: Double
    ): String? {
        require(splitArcLengthInAbMeters.isFinite() &&
            splitArcLengthInAbMeters in 0.0..rail.curve.totalArcLength)
        require(lockingDistanceMeters.isFinite() && lockingDistanceMeters >= 0.0)

        if (isTurnoutEndpoint(rail.viewBA) &&
            splitArcLengthInAbMeters + Curve.TOLERANCE < lockingDistanceMeters
        ) {
            return clearanceMessage("A", splitArcLengthInAbMeters, lockingDistanceMeters)
        }
        val distanceFromB = rail.curve.totalArcLength - splitArcLengthInAbMeters
        if (isTurnoutEndpoint(rail.viewAB) &&
            distanceFromB + Curve.TOLERANCE < lockingDistanceMeters
        ) {
            return clearanceMessage("B", distanceFromB, lockingDistanceMeters)
        }
        return null
    }

    private fun clearanceMessage(endpoint: String, available: Double, required: Double): String =
        "分割后道岔 $endpoint 端锁定区仅 ${"%.3f".format(available)}m，" +
            "小于默认要求 ${"%.3f".format(required)}m"
}
