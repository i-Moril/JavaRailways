package work.art1st.mineRails.basic.pathFinding

import work.art1st.mineRails.core.api.physics.rail.RailLocation
import work.art1st.mineRails.core.api.physics.rail.RailObject
import work.art1st.mineRails.core.api.physics.rail.RailView

class RailDestination(
    val railView: RailView,
    val label: String
) : RailObject {
    override val location: RailLocation
        get() = railView.getRailLocationAt(0.0)
}