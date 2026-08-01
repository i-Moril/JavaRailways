package work.art1st.javarailways.core.api.render

import org.joml.Vector3dc
import work.art1st.javarailways.core.api.physics.WorldElement

interface DynamicRenderable : Renderable {
    var lastState: RenderableStateSnapshot
}
