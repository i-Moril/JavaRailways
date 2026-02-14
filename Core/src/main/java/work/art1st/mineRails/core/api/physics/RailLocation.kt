package work.art1st.mineRails.core.api.physics

import work.art1st.mineRails.core.api.math.Vector3D
import work.art1st.mineRails.core.physics.rail.Rail
import work.art1st.mineRails.core.physics.rail.RailView

interface RailLocation {
    val rail: Rail
    val position: Vector3D
    val tangent: Vector3D
    val normal: Vector3D

    fun arcLengthIn(facing: RailView.ViewDirection): Double
}