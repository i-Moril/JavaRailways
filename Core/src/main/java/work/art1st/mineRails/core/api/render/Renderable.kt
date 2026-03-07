package work.art1st.mineRails.core.api.render

import org.joml.Vector3dc
import work.art1st.mineRails.core.api.physics.WorldElement
import work.art1st.mineRails.core.physics.CoreWorld
import work.art1st.mineRails.core.util.Destroyable

interface Renderable : Destroyable, WorldElement {
    val position: Vector3dc
    val direction: Vector3dc
    val normal: Vector3dc
    val velocity: Vector3dc
}
