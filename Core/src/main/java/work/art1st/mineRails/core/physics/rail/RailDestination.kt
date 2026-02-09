package work.art1st.mineRails.core.physics.rail

import work.art1st.mineRails.core.api.physics.RailObject

class RailDestination(
    override val location: RailLocation,
    val label: String
) : RailObject {
}