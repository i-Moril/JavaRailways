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
