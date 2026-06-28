package work.art1st.mineRails.core.api.physics.rail

import org.joml.Vector3dc

interface RailLocation {
    val rail: Rail
    val position: Vector3dc
    val tangent: Vector3dc
    val normal: Vector3dc

    fun arcLengthIn(facing: ViewDirection): Double
}