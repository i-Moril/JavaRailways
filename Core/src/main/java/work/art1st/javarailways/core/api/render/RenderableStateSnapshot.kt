package work.art1st.javarailways.core.api.render

import org.joml.Vector3dc
import work.art1st.javarailways.core.api.physics.World

data class RenderableStateSnapshot(
    val position: Vector3dc,
    val direction: Vector3dc,
    val normal: Vector3dc,
    val velocity: Vector3dc,
    val world: World
)
