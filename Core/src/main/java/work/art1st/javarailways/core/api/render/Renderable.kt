package work.art1st.javarailways.core.api.render

import org.joml.Vector3dc
import work.art1st.javarailways.core.api.physics.WorldElement
import work.art1st.javarailways.core.api.util.Destroyable

interface Renderable : Destroyable, WorldElement {
    val position: Vector3dc
    val direction: Vector3dc
    val normal: Vector3dc
    /** World-space linear velocity in metres per second. */
    val velocity: Vector3dc
}
