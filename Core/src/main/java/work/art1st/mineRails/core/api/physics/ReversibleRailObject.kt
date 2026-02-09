package work.art1st.mineRails.core.api.physics

import work.art1st.mineRails.core.physics.rail.RailLocationWithFacing

interface ReversibleRailObject : RailObject {
    override var location: RailLocationWithFacing
    fun reverse() {
        location = location.reversed
    }
}
