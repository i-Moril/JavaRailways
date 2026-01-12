package work.art1st.mineRails.core.physics.rail

interface ReversibleRailObject : RailObject {
    override var location: RailLocationWithFacing
    fun reverse() {
        location = location.reversed
    }
}
