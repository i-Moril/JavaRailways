package work.art1st.javarailways.core.api.physics.rail

import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve

interface RailView {
    val rail: Rail
    val viewDirection: ViewDirection
    val curve: PrimeCurve
    val nextRails: List<RailView>
    val nextActiveRail: RailView?
    val reversed: RailView
    val startPoint: Curve.Point
    val endPoint: Curve.Point
    fun getRailLocationAt(arcLength: Double): RailLocation
    fun selectNextRail(nextRail: RailView?)
}
