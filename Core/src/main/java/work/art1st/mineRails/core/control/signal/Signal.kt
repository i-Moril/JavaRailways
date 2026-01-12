package work.art1st.mineRails.core.control.signal

import work.art1st.mineRails.core.asset.Renderable
import work.art1st.mineRails.core.physics.World
import work.art1st.mineRails.core.physics.rail.RailLocation
import work.art1st.mineRails.core.physics.rail.RailObject
import work.art1st.mineRails.core.util.Vector3D

abstract class Signal(
    override val location: RailLocation
) : Renderable, RailObject {
    override val position: Vector3D
        get() = location.position
    override val direction: Vector3D
        get() = location.tangent
    override val normal: Vector3D
        get() = location.normal
    override val world: World
        get() = location.rail.world
}