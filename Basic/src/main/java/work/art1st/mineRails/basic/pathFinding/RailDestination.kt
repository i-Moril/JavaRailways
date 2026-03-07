package work.art1st.mineRails.basic.pathFinding

import work.art1st.mineRails.core.api.physics.rail.RailObject
import work.art1st.mineRails.core.physics.rail.RailLocationWithoutFacing

class RailDestination(
    override val location: RailLocationWithoutFacing,
    val label: String
) : RailObject {
}