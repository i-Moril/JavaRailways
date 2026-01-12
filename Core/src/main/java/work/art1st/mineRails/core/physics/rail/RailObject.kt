package work.art1st.mineRails.core.physics.rail

interface RailObject {
    val location: RailLocation

    val position
        get() = location.position

    val direction
        get() = location.tangent

    val normal
        get() = location.normal
}
