package work.art1st.javarailways.core.api.render

import org.joml.Vector3dc
import work.art1st.javarailways.core.api.physics.WorldElement

interface Renderable : WorldElement {
    val position: Vector3dc
    val direction: Vector3dc
    val normal: Vector3dc
    /** World-space linear velocity in metres per second. */
    val velocity: Vector3dc
}
