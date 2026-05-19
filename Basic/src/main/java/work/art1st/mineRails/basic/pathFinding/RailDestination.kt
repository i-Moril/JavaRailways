package work.art1st.mineRails.basic.pathFinding

import work.art1st.mineRails.core.api.physics.rail.RailObject
import work.art1st.mineRails.core.physics.rail.RailLocationWithFacing
import work.art1st.mineRails.core.physics.rail.RailView

class RailDestination(
    val railView: RailView,
    val label: String
) : RailObject {
    override val location: RailLocationWithFacing
        get() = railView.getRailLocationAt(0)
}