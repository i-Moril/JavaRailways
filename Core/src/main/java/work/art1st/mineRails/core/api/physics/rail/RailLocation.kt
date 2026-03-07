package work.art1st.mineRails.core.api.physics.rail

import org.joml.Vector3dc
import work.art1st.mineRails.core.physics.rail.CoreRail
import work.art1st.mineRails.core.physics.rail.RailView

interface RailLocation {
    val rail: CoreRail
    val position: Vector3dc
    val tangent: Vector3dc
    val normal: Vector3dc

    fun arcLengthIn(facing: RailView.ViewDirection): Double
}