package work.art1st.mineRails.core.api.physics

import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.mineRails.core.physics.rail.Rail
import work.art1st.mineRails.core.physics.rail.RailView

interface RailLocation {
    val rail: Rail
    val position: Vector3dc
    val tangent: Vector3dc
    val normal: Vector3dc

    fun arcLengthIn(facing: RailView.ViewDirection): Double
}